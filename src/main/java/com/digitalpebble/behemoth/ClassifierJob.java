/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.digitalpebble.behemoth;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.filecache.DistributedCache;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.digitalpebble.behemoth.util.CorpusFilter;
import com.digitalpebble.classification.Document;
import com.digitalpebble.classification.TextClassifier;
import com.digitalpebble.classification.util.Tokenizer;

/**
 * Adds a document feature for the whole document based on the text it contains
 * using a model generated by the TextClassification API
 **/
public class ClassifierJob extends Configured implements Tool {

    public static final String modelNameParam = "textclassif.model.name";

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(BehemothConfiguration.create(),
                new ClassifierJob(), args);
        System.exit(res);
    }

    public int run(String[] args) throws Exception {

        Options options = new Options();
        // automatically generate the help statement
        HelpFormatter formatter = new HelpFormatter();
        // create the parser
        CommandLineParser parser = new GnuParser();

        options.addOption("h", "help", false, "print this message");
        options.addOption("i", "input", true, "input Behemoth corpus");
        options.addOption("o", "output", true, "output Behemoth corpus");
        options.addOption("m", "model", true, "location of the model");

        // parse the command line arguments
        CommandLine line = null;
        try {
            line = parser.parse(options, args);
            String input = line.getOptionValue("i");
            String output = line.getOptionValue("o");
            String model = line.getOptionValue("m");
            if (line.hasOption("help")) {
                formatter.printHelp("ClassifierJob", options);
                return 0;
            }
            if (model == null | input == null | output == null) {
                formatter.printHelp("ClassifierJob", options);
                return -1;
            }
        } catch (ParseException e) {
            formatter.printHelp("ClassifierJob", options);
        }

        final FileSystem fs = FileSystem.get(getConf());

        Path inputPath = new Path(line.getOptionValue("i"));
        Path outputPath = new Path(line.getOptionValue("o"));
        String modelPath = line.getOptionValue("m");

        // TODO pass the reference of the model to the mapper

        JobConf job = new JobConf(getConf());
        job.setJarByClass(this.getClass());

        job.setJobName("ClassifierJob : " + inputPath.toString());

        job.setInputFormat(SequenceFileInputFormat.class);
        job.setOutputFormat(SequenceFileOutputFormat.class);

        job.setMapOutputKeyClass(Text.class);
        job.setMapOutputValueClass(BehemothDocument.class);
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(BehemothDocument.class);

        job.setMapperClass(TextClassifierMapper.class);
        job.setNumReduceTasks(0);

        FileInputFormat.addInputPath(job, inputPath);
        FileOutputFormat.setOutputPath(job, outputPath);

        job.set(modelNameParam, modelPath);
        // push the UIMA pear onto the DistributedCache
        DistributedCache.addCacheFile(new URI(modelPath), job);

        try {
            JobClient.runJob(job);
        } catch (Exception e) {
            e.printStackTrace();
            fs.delete(outputPath, true);
        } finally {
        }

        return 0;
    }

}

class TextClassifierMapper extends MapReduceBase implements
        Mapper<Text, BehemothDocument, Text, BehemothDocument> {

    DocumentFilter filter;
    TextClassifier classifier;
    boolean lowerCase = false;
    String docFeaturename = "label";

    private static final Logger LOG = LoggerFactory
            .getLogger(TextClassifierMapper.class);

    public void map(Text key, BehemothDocument doc,
            OutputCollector<Text, BehemothDocument> collector, Reporter reported)
            throws IOException {
        // get the text
        if (doc.getText() == null | doc.getText().length() < 2) {
            reported.incrCounter("text classification", "MISSING TEXT", 1);
            collector.collect(key, doc);
            return;
        }
        // use the quick and dirty tokenization
        String[] tokens = Tokenizer.tokenize(doc.getText(), lowerCase);
        // TODO use annotations instead?
        Document tcdoc = classifier.createDocument(tokens);
        double[] scores;
        try {
            scores = classifier.classify(tcdoc);
        } catch (Exception e) {
            e.printStackTrace();
            collector.collect(key, doc);
            reported.incrCounter("text classification", "EXCEPTION", 1);
            return;
        }
        String label = classifier.getBestLabel(scores);
        doc.getMetadata(true).put(new Text(docFeaturename), new Text(label));
        collector.collect(key, doc);
        reported.incrCounter("text classification", label, 1);
    }

    @Override
    public void configure(JobConf job) {
        super.configure(job);
        filter = DocumentFilter.getFilters(job);
        lowerCase = job.getBoolean("classification.tokenize", false);
        docFeaturename = job.get("classification.doc.feature.name", "label");

        String modelPath = job.get(ClassifierJob.modelNameParam);

        URL modelURL = null;

        // the application will have been unzipped and put on the distributed
        // cache
        try {
            Path[] localArchives = DistributedCache.getLocalCacheArchives(job);
            if (localArchives==null){
                // local mode? try and read direct from the path
                modelURL = new URL("file://" +modelPath);
            }
            // identify the right archive
            else for (Path la : localArchives) {
                String localPath = la.toUri().toString();
                LOG.info("LocalCache : " + localPath);
                if (!localPath.endsWith(modelPath))
                    continue;
                modelURL = new URL("file://" + localPath);
                break;
            }
            
            classifier = classifier.getClassifier(modelURL.getPath());
            
        } catch (Exception e) {
            throw new RuntimeException(
                    "Impossible to retrieve model from distributed cache", e);
        }
        

    }

}
