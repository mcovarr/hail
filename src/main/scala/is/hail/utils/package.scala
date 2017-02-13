package is.hail

import java.lang.reflect.Method
import java.net.URI

import is.hail.check.Gen
import is.hail.driver.HailContext
import org.apache.hadoop.fs.PathIOException
import org.apache.hadoop.mapred.FileSplit
import org.apache.hadoop.mapreduce.lib.input.{FileSplit => NewFileSplit}
import org.apache.spark.{AccumulableParam, Partition}
import org.json4s.Extraction.decompose
import org.json4s.jackson.Serialization
import org.json4s.{Formats, JValue, NoTypeHints}

import scala.collection.generic.CanBuildFrom
import scala.collection.{GenTraversableOnce, TraversableOnce, mutable}
import scala.language.{higherKinds, implicitConversions}
import scala.reflect.ClassTag

package object utils extends Logging
  with richUtils.Implicits
  with utils.NumericImplicits
  with Py4jUtils
  with ErrorHandling {

  class FatalException(val msg: String, val logMsg: Option[String] = None) extends RuntimeException(msg)


  trait Truncatable {
    def truncate: String

    def strings: (String, String)
  }

  def format(s: String, substitutions: Any*): String = {
    substitutions.zipWithIndex.foldLeft(s) { case (str, (value, i)) =>
      str.replace(s"@${ i + 1 }", value.toString)
    }
  }

  def plural(n: Int, sing: String, plur: String = null): String =
    if (n == 1)
      sing
    else if (plur == null)
      sing + "s"
    else
      plur

  def square[T](d: T)(implicit ev: T => scala.math.Numeric[T]#Ops): T = d * d

  def triangle(n: Int): Int = (n * (n + 1)) / 2

  def treeAggDepth(hc: HailContext, nPartitions: Int): Int =
    (math.log(nPartitions) / math.log(hc.branchingFactor) + 0.5).toInt.max(1)

  def simpleAssert(p: Boolean) {
    if (!p) throw new AssertionError
  }

  def optionCheckInRangeInclusive[A](low: A, high: A)(name: String, a: A)(implicit ord: Ordering[A]): Unit =
    if (ord.lt(a, low) || ord.gt(a, high)) {
      fatal(s"$name cannot lie outside [$low, $high]: $a")
    }

  def printTime[T](block: => T) = {
    val timed = time(block)
    println("time: " + formatTime(timed._2))
    timed._1
  }

  def time[A](f: => A): (A, Long) = {
    val t0 = System.nanoTime()
    val result = f
    val t1 = System.nanoTime()
    (result, t1 - t0)
  }

  final val msPerMinute = 60 * 1e3
  final val msPerHour = 60 * msPerMinute
  final val msPerDay = 24 * msPerHour

  def formatTime(dt: Long): String = {
    val tMilliseconds = dt / 1e6
    if (tMilliseconds < 1000)
      ("%.3f" + "ms").format(tMilliseconds)
    else if (tMilliseconds < msPerMinute)
      ("%.3f" + "s").format(tMilliseconds / 1e3)
    else if (tMilliseconds < msPerHour) {
      val tMins = (tMilliseconds / msPerMinute).toInt
      val tSec = (tMilliseconds % msPerMinute) / 1e3
      ("%d" + "m" + "%.1f" + "s").format(tMins, tSec)
    }
    else {
      val tHrs = (tMilliseconds / msPerHour).toInt
      val tMins = ((tMilliseconds % msPerHour) / msPerMinute).toInt
      val tSec = (tMilliseconds % msPerMinute) / 1e3
      ("%d" + "h" + "%d" + "m" + "%.1f" + "s").format(tHrs, tMins, tSec)
    }
  }

  def space[A](f: => A): (A, Long) = {
    val rt = Runtime.getRuntime
    System.gc()
    System.gc()
    val before = rt.totalMemory() - rt.freeMemory()
    val r = f
    System.gc()
    val after = rt.totalMemory() - rt.freeMemory()
    (r, after - before)
  }

  def printSpace[A](f: => A): A = {
    val (r, ds) = space(f)
    println("space: " + formatSpace(ds))
    r
  }

  def formatSpace(ds: Long) = {
    val absds = ds.abs
    if (absds < 1e3)
      s"${ ds }B"
    else if (absds < 1e6)
      s"${ ds.toDouble / 1e3 }KB"
    else if (absds < 1e9)
      s"${ ds.toDouble / 1e6 }MB"
    else if (absds < 1e12)
      s"${ ds.toDouble / 1e9 }GB"
    else
      s"${ ds.toDouble / 1e12 }TB"
  }

  def someIf[T](p: Boolean, x: => T): Option[T] =
    if (p)
      Some(x)
    else
      None

  def nullIfNot(p: Boolean, x: Any): Any = {
    if (p)
      x
    else
      null
  }

  def divOption[T](num: T, denom: T)(implicit ev: T => Double): Option[Double] =
    someIf(denom != 0, ev(num) / denom)

  def divNull[T](num: T, denom: T)(implicit ev: T => Double): Any =
    nullIfNot(denom != 0, ev(num) / denom)

  val defaultTolerance = 1e-6

  def D_epsilon(a: Double, b: Double, tolerance: Double = defaultTolerance): Double =
    math.max(java.lang.Double.MIN_NORMAL, tolerance * math.max(math.abs(a), math.abs(b)))

  def D_==(a: Double, b: Double, tolerance: Double = defaultTolerance): Boolean = {
    a == b || math.abs(a - b) <= D_epsilon(a, b, tolerance)
  }

  def D_!=(a: Double, b: Double, tolerance: Double = defaultTolerance): Boolean = {
    !(a == b) && math.abs(a - b) > D_epsilon(a, b, tolerance)
  }

  def D_<(a: Double, b: Double, tolerance: Double = defaultTolerance): Boolean =
    !(a == b) && a - b < -D_epsilon(a, b, tolerance)

  def D_<=(a: Double, b: Double, tolerance: Double = defaultTolerance): Boolean =
    (a == b) || a - b <= D_epsilon(a, b, tolerance)

  def D_>(a: Double, b: Double, tolerance: Double = defaultTolerance): Boolean =
    !(a == b) && a - b > D_epsilon(a, b, tolerance)

  def D_>=(a: Double, b: Double, tolerance: Double = defaultTolerance): Boolean =
    (a == b) || a - b >= -D_epsilon(a, b, tolerance)

  def flushDouble(a: Double): Double =
    if (math.abs(a) < java.lang.Double.MIN_NORMAL) 0.0 else a

  def genBase: Gen[Char] = Gen.oneOf('A', 'C', 'T', 'G')

  def getPartNumber(fname: String): Int = {
    val partRegex = """.*/?part-(\d+).*""".r

    fname match {
      case partRegex(i) => i.toInt
      case _ => throw new PathIOException(s"invalid partition file `$fname'")
    }
  }

  def getParquetPartNumber(fname: String): Int = {
    val parquetRegex = ".*/?part-(r-)?(\\d+)-.*\\.parquet.*".r

    fname match {
      case parquetRegex(_, i) => i.toInt
      case _ => throw new PathIOException(s"invalid parquet file `$fname'")
    }
  }

  // ignore size; atomic, like String
  def genDNAString: Gen[String] = Gen.stringOf(genBase)
    .resize(12)
    .filter(s => !s.isEmpty)

  implicit def accumulableMapInt[K]: AccumulableParam[mutable.Map[K, Int], K] = new AccumulableParam[mutable.Map[K, Int], K] {
    def addAccumulator(r: mutable.Map[K, Int], t: K): mutable.Map[K, Int] = {
      r.updateValue(t, 0, _ + 1)
      r
    }

    def addInPlace(r1: mutable.Map[K, Int], r2: mutable.Map[K, Int]): mutable.Map[K, Int] = {
      for ((k, v) <- r2)
        r1.updateValue(k, 0, _ + v)
      r1
    }

    def zero(initialValue: mutable.Map[K, Int]): mutable.Map[K, Int] =
      mutable.Map.empty[K, Int]
  }


  def prettyIdentifier(str: String): String = {
    if (str.matches( """\p{javaJavaIdentifierStart}\p{javaJavaIdentifierPart}*"""))
      str
    else
      s"`${ StringEscapeUtils.escapeString(str, backticked = true) }`"
  }

  def uriPath(uri: String): String = new URI(uri).getPath

  def extendOrderingToNull[T](implicit ord: Ordering[T]): Ordering[Any] = {
    new Ordering[Any] {
      def compare(a: Any, b: Any) =
        (a, b) match {
          case (null, null) => 0
          case (null, _) => 1
          case (_, null) => -1
          case _ => ord.compare(a.asInstanceOf[T], b.asInstanceOf[T])
        }
    }
  }

  def flattenOrNull[C[_] >: Null, T >: Null](b: mutable.Builder[T, C[T]], it: Iterable[Iterable[T]]): C[T] = {
    for (elt <- it) {
      if (elt == null)
        return null
      b ++= elt
    }
    b.result()
  }

  def anyFailAllFail[C[_], T](ts: TraversableOnce[Option[T]])(implicit cbf: CanBuildFrom[Nothing, T, C[T]]): Option[C[T]] = {
    val b = cbf()
    for (t <- ts) {
      if (t.isEmpty)
        return None
      else
        b += t.get
    }
    Some(b.result())
  }


  def uninitialized[T]: T = {
    class A {
      var x: T = _
    }
    (new A).x
  }

  def mapAccumulate[C[_], T, S, U](a: Iterable[T], z: S)(f: (T, S) => (U, S))(implicit uct: ClassTag[U],
    cbf: CanBuildFrom[Nothing, U, C[U]]): C[U] = {
    val b = cbf()
    var acc = z
    for ((x, i) <- a.zipWithIndex) {
      val (y, newAcc) = f(x, acc)
      b += y
      acc = newAcc
    }
    b.result()
  }

  /**
    * An abstraction for building an {@code Array} of known size. Guarantees a left-to-right traversal
    *
    * @param xs the thing to iterate over
    * @param size the size of array to allocate
    * @param key given the source value and its source index, yield the target index
    * @param combine given the target value, the target index, the source value, and the source index, compute the new target value
    * @tparam A
    * @tparam B
    */
  def coalesce[A, B: ClassTag](xs: GenTraversableOnce[A])(size: Int, key: (A, Int) => Int, z: B)(combine: (B, A) => B): Array[B] = {
    val a = Array.fill(size)(z)

    for ((x, idx) <- xs.toIterator.zipWithIndex) {
      val k = key(x, idx)
      a(k) = combine(a(k), x)
    }

    a
  }

  def mapSameElements[K, V](l: Map[K, V], r: Map[K, V], valueEq: (V, V) => Boolean): Boolean = {
    def entryMismatchMessage(failures: TraversableOnce[(K, V, V)]): String = {
      require(failures.nonEmpty)
      val newline = System.lineSeparator()
      val sb = new StringBuilder
      sb ++= "The maps do not have the same entries:" + newline
      for (failure <- failures) {
        sb ++= s"  At key ${ failure._1 }, the left map has ${ failure._2 } and the right map has ${ failure._3 }" + newline
      }
      sb ++= s"  The left map is: $l" + newline
      sb ++= s"  The right map is: $r" + newline
      sb.result()
    }

    if (l.keySet != r.keySet) {
      println(
        s"""The maps do not have the same keys.
           |  These keys are unique to the left-hand map: ${ l.keySet -- r.keySet }
           |  These keys are unique to the right-hand map: ${ r.keySet -- l.keySet }
           |  The left map is: $l
           |  The right map is: $r
      """.stripMargin)
      false
    } else {
      val fs = Array.newBuilder[(K, V, V)]
      for ((k, lv) <- l) {
        val rv = r(k)
        if (!valueEq(lv, rv))
          fs += ((k, lv, rv))
      }
      val failures = fs.result()

      if (!failures.isEmpty) {
        println(entryMismatchMessage(failures))
        false
      } else {
        true
      }
    }
  }

  def getIteratorSize[T](iterator: Iterator[T]): Long = {
    var count = 0L
    while (iterator.hasNext) {
      count += 1L
      iterator.next()
    }
    count
  }

  def lookupMethod(c: Class[_], method: String): Method = {
    try {
      c.getDeclaredMethod(method)
    } catch {
      case _: Exception =>
        assert(c != classOf[java.lang.Object])
        lookupMethod(c.getSuperclass, method)
    }
  }

  def invokeMethod(obj: AnyRef, method: String, args: AnyRef*): AnyRef = {
    val m = lookupMethod(obj.getClass, method)
    m.invoke(obj, args: _*)
  }

  /*
   * Use reflection to get the path of a partition coming from a Parquet read.  This requires accessing Spark
   * internal interfaces.  It works with Spark 1 and 2 and doesn't depend on the location of the Parquet
   * package (parquet vs org.apache.parquet) which can vary between distributions.
   */
  def partitionPath(p: Partition): String = {
    p.getClass.getCanonicalName match {
      case "org.apache.spark.rdd.SqlNewHadoopPartition" =>
        val split = invokeMethod(invokeMethod(p, "serializableHadoopSplit"), "value").asInstanceOf[NewFileSplit]
        split.getPath.getName

      case "org.apache.spark.sql.execution.datasources.FilePartition" =>
        val files = invokeMethod(p, "files").asInstanceOf[Seq[_ <: AnyRef]]
        assert(files.length == 1)
        invokeMethod(files(0), "filePath").asInstanceOf[String]

      case "org.apache.spark.rdd.HadoopPartition" =>
        val split = invokeMethod(invokeMethod(p, "inputSplit"), "value").asInstanceOf[FileSplit]
        split.getPath.getName
    }
  }

  implicit val jsonFormatsNoTypeHints: Formats = Serialization.formats(NoTypeHints)

  def caseClassJSONReaderWriter[T](implicit mf: scala.reflect.Manifest[T]): JSONReaderWriter[T] = new JSONReaderWriter[T] {
    def toJSON(x: T): JValue = decompose(x)

    def fromJSON(jv: JValue): T = jv.extract[T]
  }

  def splitWarning(leftSplit: Boolean, left: String, rightSplit: Boolean, right: String) {
    val msg =
      """Merge behavior may not be as expected, as all alternate alleles are
        |  part of the variant key.  See `annotatevariants' documentation for
        |  more information.""".stripMargin
    (leftSplit, rightSplit) match {
      case (true, true) =>
      case (false, false) => warn(
        s"""annotating an unsplit $left from an unsplit $right
           |  $msg""".stripMargin)
      case (true, false) => warn(
        s"""annotating a biallelic (split) $left from an unsplit $right
           |  $msg""".stripMargin)
      case (false, true) => warn(
        s"""annotating an unsplit $left from a biallelic (split) $right
           |  $msg""".stripMargin)
    }
  }
}
