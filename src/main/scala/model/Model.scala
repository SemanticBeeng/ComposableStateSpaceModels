package model

import model.DataTypes._
import java.io.Serializable
import model.POMP._
import model.Model._
import breeze.stats.distributions.{Rand, Density}

trait Model extends Serializable {
  // The observation model
  def observation: Eta => Rand[Observation]
  // initialise the "transformed" state to go into step two
  def initState: State = LeafState(Vector())
  // transforms the parameters into a suitable state for the dataLikelihood and observation model
  def stepTwo: (Eta, State, TimeIncrement) => State =
    (x, s, dt) => LeafState(Vector(x))
  // the link function
  def link(x: Gamma): Eta = x
  // deterministic transformation, such as seasonality
  def f(s: State, t: Time): Gamma
  // initialise the SDE state
  def x0: Rand[State]
  // Step the SDE
  def stepFunction: (State, TimeIncrement) => Rand[State]
  // calculate the likelihood of the observation given the state
  def dataLikelihood: (State, Observation) => LogLikelihood

  // def |+|(that: Model): Model = {
  //   op(this, that)
  // }
}

object Model {
  def op(mod1: Parameters => Model, mod2: Parameters => Model): Parameters => Model = p => new Model {

    def observation = x => p match {
      case BranchParameter(lp,_) => mod1(lp).observation(x)
    }

    override def link(x: Double): Double = mod1(p).link(x)

    // StepTwo outputs a single state
    override def stepTwo = mod1(p).stepTwo

    override def initState = mod1(p).initState

    def f(s: State, t: Time) = s match {
      case BranchState(ls, rs) =>
        mod1(p).f(ls, t) + mod2(p).f(rs, t)
      case x: LeafState =>
        mod1(p).f(x, t)
    }

    def x0 = new Rand[State] {
      def draw = {
        p match {
          case BranchParameter(lp, rp) =>
            val (l, r) = (mod1(lp).x0.draw, mod2(rp).x0.draw)
              (l, r) match {
              case (x: LeafState, y: LeafState) if x.isEmpty => y
              case (x: LeafState, y: LeafState) if y.isEmpty => x
              case (x: LeafState, y: LeafState) => BranchState(x, y)
              case (x: BranchState, y: LeafState) => BranchState(x, y)
              case (x: LeafState, y: BranchState) => BranchState(x, y)
            }
          case param: LeafParameter =>
            val (l, r) = (mod1(param).x0, mod2(param).x0)
              (l, r) match {
              case (x: LeafState, y: LeafState) if x.isEmpty => y
              case (x: LeafState, y: LeafState) if y.isEmpty => x
              case (x: LeafState, y: LeafState) => BranchState(x, y)
              case (x: BranchState, y: LeafState) => BranchState(x, y)
              case (x: LeafState, y: BranchState) => BranchState(x, y)
            }
        }
      }
    }

    def stepFunction = (s, dt) => (s, p) match {
      case (BranchState(ls, rs), BranchParameter(lp, rp)) =>
        for {
          l <- mod1(lp).stepFunction(ls, dt)
          r <- mod2(rp).stepFunction(rs, dt)
        } yield BranchState(l, r)
      case (x: LeafState, param: LeafParameter) => // Null model case, non-null must be on left
        mod1(param).stepFunction(x, dt)
    }

     def dataLikelihood = (s, y) => p match {
      case param: LeafParameter => mod1(param).dataLikelihood(s, y)
      case BranchParameter(lp, _) => mod1(lp).dataLikelihood(s, y)
      }
  }

  def zeroModel(stepFun: SdeParameter => (State, TimeIncrement) => Rand[State]): Parameters => Model = p => new Model {
    def observation = x => new Rand[Observation] { def draw = x }
    def f(s: State, t: Time) = s.head
    def x0 = new Rand[State] { def draw = LeafState(Vector[Double]()) }
    def stepFunction = p match {
      case LeafParameter(_,_,sdeparam  @unchecked) => stepFun(sdeparam)
    }

    def dataLikelihood = (s, y) => 0.0
  }
}
