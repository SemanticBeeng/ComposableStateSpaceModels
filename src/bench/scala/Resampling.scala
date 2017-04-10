import org.scalameter.api._
import com.github.jonnylaw.model._
import org.scalameter.picklers.Implicits._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import cats.implicits._
import scala.collection.parallel.immutable.ParVector

object ResamplingBenchmark extends Bench.LocalTime {
  val sizes: Gen[Int] = Gen.exponential("size")(100, 6400, 2)

  val threads = Gen.enumeration("threads")(1, 2, 4)

  def input = for {
    size <- sizes
  } yield (Vector.range(1, size + 1), Vector.fill(size)(1.0))

  val input1 = for {
    pw <- input
    n <- threads
  } yield (pw._1, pw._2, n)

  performance of "Async Tree Map Systematic Resampling" in {
    using(input1) in { case (s, w, n) =>
      Await.result(Resampling.asyncTreeSystematicResampling(n)(s, w), Duration.Inf)
    }
  }

  performance of "Serial Tree Map Systematic Resampling With Vector" in {
    using(input) in { case (s, w) =>
      Resampling.treeSystematicResampling(s, w)
    }
  }

  performance of "Multinomial Resampling" in {
    using(input) in { case (s, w) =>
      Resampling.multinomialResampling(s, w)
    }
  }

}
