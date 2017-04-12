package com.github.jonnylaw.model

import breeze.linalg.DenseVector
import cats.implicits._
import spray.json._
import scala.util.{Try, Success, Failure}
import org.joda.time.DateTime   
import com.github.nscala_time.time.Imports._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport

/**
  * Marshalling from JSON and to JSON for Simulated Data and MCMC data 
  * from the Composed Models Package
  */
object DataProtocols extends SprayJsonSupport with DefaultJsonProtocol {

  implicit def denseVectorFormat = new RootJsonFormat[DenseVector[Double]] {
    def write(vec: DenseVector[Double]) = JsArray(vec.data.map(_.toJson).toVector)
    def read(value: JsValue) = value match {
      case JsArray(elements) => {
        val s: Array[Double] = elements.map(_.convertTo[Double]).toArray[Double]
        DenseVector(s)
      }
      case x => deserializationError("Expected DenseVector as JsArray, but got " + x)
    }
  }

  implicit val brownianFormat = jsonFormat3(BrownianParameter.apply)
  implicit val genbrownianFormat = jsonFormat4(GenBrownianParameter.apply)
  implicit val ornFormat = jsonFormat5(OuParameter.apply)

  implicit def sdeParamFormat = new RootJsonFormat[SdeParameter] {
   def write(obj: SdeParameter): JsValue = obj match {
     case b: BrownianParameter => b.toJson
     case gb: GenBrownianParameter => gb.toJson
     case ou: OuParameter => ou.toJson
   }

    def read(value: JsValue): SdeParameter = value match {
      case obj: JsObject if (obj.fields.size == 3) => value.convertTo[BrownianParameter]
      case obj: JsObject if (obj.fields.size == 4) => value.convertTo[GenBrownianParameter]
      case obj: JsObject => value.convertTo[OuParameter]
    }
  }

  implicit val leafParamFormat = jsonFormat2(LeafParameter.apply)
  implicit val branchParamFormat: JsonFormat[BranchParameter] = lazyFormat(jsonFormat2(BranchParameter.apply))

  implicit def paramsFormat = new RootJsonFormat[Parameters] {
    def write(obj: Parameters) = {
      def loop(params: Parameters): Vector[JsValue] = params match {
        case l: LeafParameter => Vector(l.toJson)
        case BranchParameter(l, r) => loop(l) ++ loop(r)
        case EmptyParameter => Vector()
      }

      JsArray(loop(obj))
    }
    def read(value: JsValue) = value match {
      case JsArray(elements) => {
        elements.
          map(_.convertTo[LeafParameter]).
          reduce((a: Parameters, b: Parameters) => a |+| b)
      }
      case x => deserializationError("Expected Some Parameters, but got " + x)
    }

  }

  implicit val leafFormat = jsonFormat1(Leaf[DenseVector[Double]])
  implicit val branchFormat: JsonFormat[Branch[DenseVector[Double]]] = lazyFormat(jsonFormat2(Branch[DenseVector[Double]]))

  implicit val stateFormat = new RootJsonFormat[State] {
    def write(obj: State) = {
      def loop(state: State): Vector[JsValue] = state match {
        case l: Leaf[DenseVector[Double]] => Vector(l.toJson)
        case Branch(l, r) => loop(l) ++ loop(r)
      }

      JsArray(loop(obj))
    }
    def read(value: JsValue) = value match {
      case JsArray(elements) => {
        elements.
          map(_.convertTo[Leaf[DenseVector[Double]]]).
          reduce((a: State, b: State) => a |+| b)
      }
      case x => deserializationError("Expected Some states, but got " + x)
    }
  }

  implicit def dateTimeJsonFormat = new RootJsonFormat[DateTime] {
    val dateTimeFormat = new DateTime()
    val formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZZ")

    def write(obj: DateTime): JsValue = {
      JsString(formatter.print(obj))
    }

    def read(json: JsValue): DateTime = json match {
      case JsString(s) => formatter.parseDateTime(s)
      case _ => deserializationError("DateTime expected")
    }
  }

  implicit val stateSpaceFormat = jsonFormat2(StateSpace)
  implicit val metropFormat = jsonFormat4(MetropState.apply)
  implicit val pmmhFormat = jsonFormat3(ParamsState.apply)
  implicit val tdFormat = jsonFormat2(TimedObservation.apply)
  implicit val osFormat = jsonFormat5(ObservationWithState.apply)
  implicit val tsFormat = jsonFormat3(TimestampObservation.apply)
  implicit val decompFormat = jsonFormat5(DecomposedModel.apply)

  implicit def dataFormat = new RootJsonFormat[Data] {
   def write(obj: Data): JsValue = obj match {
     case t: TimedObservation => t.toJson
     case o: ObservationWithState => o.toJson
     case ts: TimestampObservation => ts.toJson
     case _ => deserializationError("Data object expected")
   }
    def read(value: JsValue) = Try(value.convertTo[TimedObservation]).
      getOrElse(value.convertTo[ObservationWithState])
  }

  implicit val intFormat = jsonFormat2(CredibleInterval.apply)

  implicit val pfOutFormat = jsonFormat6(PfOut.apply)

  implicit val pfStateFormat = jsonFormat5(PfState.apply)
}
