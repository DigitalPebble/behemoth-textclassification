behemoth-textclassification
====================

INSTRUCTIONS
- git clone git@github.com:DigitalPebble/behemoth
- mvn package
(note : this won't be required when behemoth-core is publicly available)
- git clone git@github.com:DigitalPebble/behemoth-textclassification.git
- mvn install:install-file -DgroupId=com.digitalpebble.textclassification -DartifactId=textclassification -Dversion=1.6 -Dpackaging=jar -Dfile=lib/TextClassificationAPI-1.6.jar
- mvn package

