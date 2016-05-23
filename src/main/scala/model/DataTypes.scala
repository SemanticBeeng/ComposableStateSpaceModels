package model

import model.POMP.Observation
import model.Utilities._
import java.io.Serializable
import scala.util.parsing.json.JSONObject
import breeze.linalg.{DenseVector, DenseMatrix, diag}

object DataTypes {
  import model.POMP._

  /**
    * A description containing the modelled quantities and observations
    * sdeState = x_t = p(x_t | x_t-1)
    * gamma = f(x_t)
    * eta = g(gamma)
    * observation = pi(eta)
    */
  case class Data(
    t: Time,
    observation: Observation,
    eta: Option[Eta],
    gamma: Option[Gamma],
    sdeState: Option[State]) {

    import Data._

    override def toString = {
      if (!sdeState.isEmpty) {
        s"$t, $observation, ${eta.get}, ${gamma.get}, " + sdeState.get.flatten.mkString(", ")
      } else {
        t + ", " + observation
      }
    }
  }

  /**
    * Given a sorted set of data, removes duplicate sequential entries 
    * by time, even if they have different observations
    */
  def removeDupTimes(data: Vector[Data]): Vector[Data] = {
    val sortedData = data.tail.foldLeft(Vector(data.head))((acc, a) => if (a.t == acc.head.t) acc else a +: acc)
    sortedData.reverse
  }

  /**
    * Representing intervals sampled from the empirical filtering distribution p(x_t | y_t)
    * @param lower the lower interval
    * @param upper the upper interval
    */
  case class CredibleInterval(lower: Double, upper: Double) {
    override def toString = lower + ", " + upper
  }

  /**
    * The structure which the particle filter returns,
    * @param time the time of the process
    * @param observation an optional observation, note discretely observed processes cannot be seen at all time points continuously
    * @param state the mean of the empirical filtering distribution at time 'time'
    * @param intervals the credible intervals of the filtering distribution
    */
  case class PfOut(time: Time, observation: Option[Observation], state: State, intervals: IndexedSeq[CredibleInterval]) {
    override def toString = s"$time, $observation, ${State.flattenState(state).mkString(", ")}, ${intervals.mkString(", ")}"
  }

  /**
    * A representation of a simulated Diffusion process
    */
  case class Sde(time: Time, state: State) {
    override def toString = time + "," + state.toString
  }
}

