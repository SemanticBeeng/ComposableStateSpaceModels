linearModel:
	sbt assembly
	cp target/scala-2.11/ComposableModels-assembly-0.4.0.jar ./LinearModel.jar
	scp LinearModel.jar maths:/home/a9169110/.
	ssh struve -t "java -cp LinearModel.jar com.github.jonnylaw.examples.SimLinearModel"
	ssh struve -t "java -cp LinearModel.jar com.github.jonnylaw.examples.FilterLinear"
	scp maths:/home/a9169110/data/LinearModelSims.csv data/.
	scp maths:/home/a9169110/data/LinearModelFiltered.csv data/.
	RScript scripts/LinearExamples.R

poissonPilot:
	sbt assembly
	cp target/scala-2.11/ComposableModels-assembly-0.4.0.jar ./PoissonModel.jar
	scp PoissonModel.jar maths:/home/a9169110/.	
	ssh a9169110@airy -t "java -cp PoissonModel.jar com.github.jonnylaw.examples.SimPoissonModel"
	ssh a9169110@airy -t "java -cp PoissonModel.jar com.github.jonnylaw.examples.PilotRunPoisson"
	scp maths:/home/a9169110/data/PoissonModelSims.csv data/.
	scp maths:/home/a9169110/data/PoissonPilotRun.csv data/.
	RScript scripts/PoissonExamples.R

seasonalModel:
	sbt assembly
	cp target/scala-2.11/ComposableModels-assembly-0.4.0.jar ./SeasonalModel.jar
	scp SeasonalModel.jar maths:/home/a9169110/.	
	ssh struve -t "java -cp SeasonalModel.jar com.github.jonnylaw.examples.SimSeasonalModel"
	ssh struve -t "ssh struve -t java -cp SeasonalModel.jar com.github.jonnylaw.examples.FilterSeasonal"
	scp maths:/home/a9169110/data/SeasonalModelSims.csv data/.
	scp maths:/home/a9169110/data/SeasonalModelFiltered.csv data/.
	RScript scripts/SeasonalExamples.R


