#!/bin/bash

sbt assembly
cp target/scala-2.11/ComposableModels-assembly-0.6.0.jar NegativeBinomial.jar
scp NegativeBinomial.jar maths:/home/a9169110/.

ssh airy -t /home/a9169110/jdk/jdk1.8.0_121/bin/java -cp NegativeBinomial.jar com.github.jonnylaw.examples.SimModelToCSV
ssh airy -f screen -S NegativeBinomial -dm /home/a9169110/jdk/jdk1.8.0_121/bin/java -cp NegativeBinomial.jar com.github.jonnylaw.examples.DeterminePosterior

ssh airy -t screen -ls
