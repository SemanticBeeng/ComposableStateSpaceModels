package com.github.jonnylaw.model

import com.github.jonnylaw.model.Utilities._
import java.io.Serializable
import scala.util.parsing.json.JSONObject
import breeze.linalg.{DenseVector, DenseMatrix, diag}

object DataTypes {
  import com.github.jonnylaw.model.POMP._

  /**
    * A description containing the modelled quantities and observations
    * @param sdeState = x_t = p(x_t | x_t-1), the latent state (Optional)
    * @param gamma = f(x_t), the latent state transformed by the linear transformation (Optional)
    * @param eta = g(gamma), the latent state transformed by the linking-function (Optional)
    * @param observation = pi(eta), the observation
    * @param t, the time of the observation
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
        s"$t, $observation, ${eta.get.head}, ${gamma.get}, " + sdeState.get.flatten.mkString(", ")
      } else {
        t + ", " + observation
      }
    }
  }

  /**
    * Given a sorted set of data, removes duplicate sequential entries 
    * by time, even if they have different observations
    * @param data a vector of data, consisting of observations
    * @return a vector of data with duplicate readings removed
    */
  def removeDupTimes(data: Vector[Data]): Vector[Data] = {
    val sortedData = data.tail.sortBy(_.t).
      foldLeft(Vector(data.head))((acc, a) => if (a.t == acc.head.t) acc else a +: acc)
    sortedData.reverse
  }

  /**
    * Credible intervals from a set of samples in a distribution
    * @param lower the lower interval
    * @param upper the upper interval
    */
  case class CredibleInterval(lower: Double, upper: Double) {
    override def toString = lower + ", " + upper
  }

  /**
    * A class representing a return type for the particle filter, containing the state and associated credible intervals
    * @param time the time of the process
    * @param observation an optional observation, note discretely observed processes cannot be seen at all time points continuously
    * @param state the mean of the empirical filtering distribution at time 'time'
    * @param intervals the credible intervals of the filtering distribution
    */
  case class PfOut(
    time: Time,
    observation: Option[Observation],
    eta: Double,
    etaIntervals: CredibleInterval,
    state: State,
    stateIntervals: IndexedSeq[CredibleInterval]) {

    override def toString = {
      observation match {
        case Some(x) =>
          s"$time, $x, $eta, ${etaIntervals.toString}, ${state.flatten.mkString(", ")}, ${stateIntervals.mkString(", ")}"
        case None =>
          s"$time, NA, $eta, ${etaIntervals.toString}, ${state.flatten.mkString(", ")}, ${stateIntervals.mkString(", ")}"
      }
    }
  }

  /**
    * Representing a realisation from a stochastic differential equation
    * @param time
    * @param state
    */
  case class Sde(time: Time, state: State) {
    override def toString = time + "," + state.toString
  }
}
