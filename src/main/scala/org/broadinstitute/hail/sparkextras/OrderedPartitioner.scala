package org.broadinstitute.hail.sparkextras

import java.io.{ObjectInputStream, ObjectOutputStream}
import java.nio.ByteBuffer
import java.util
import java.util.{Random => JavaRandom}

import org.apache.spark.Partitioner
import org.apache.spark.rdd.RDD

import scala.collection.mutable
import scala.reflect.{ClassTag, classTag}
import scala.util.Random
import scala.util.hashing.{MurmurHash3, byteswap32}

case class OrderedPartitioner[T, K](
  rangeBounds: Array[T],
  projectKey: (K) => T,
  ascending: Boolean = true)(implicit tOrd: Ordering[T], kOrd: Ordering[K], tct: ClassTag[T], kct: ClassTag[K])
  extends Partitioner {

  import Ordering.Implicits._

  require(rangeBounds.isEmpty ||
    rangeBounds.zip(rangeBounds.tail).forall { case (left, right) => left < right })

  def numPartitions: Int = rangeBounds.length + 1

  var binarySearch: ((Array[T], T) => Int) = OrderedPartitioner.makeBinarySearch[T]

  def getPartition(key: Any): Int = getPartitionT(projectKey(key.asInstanceOf[K]))

  /**
    * Code mostly copied from:
    *   org.apache.spark.RangePartitioner.getPartition(key: Any)
    *   version 1.5.0
    **/
  def getPartitionT(key: T): Int = {

    var partition = 0
    if (rangeBounds.length <= 128) {
      // If we have less than 128 partitions naive search
      while (partition < rangeBounds.length && key > rangeBounds(partition)) {
        partition += 1
      }
    } else {
      // Determine which binary search method to use only once.
      partition = binarySearch(rangeBounds, key)
      // binarySearch either returns the match location or -[insertion point]-1
      if (partition < 0) {
        partition = -partition - 1
      }
      if (partition > rangeBounds.length) {
        partition = rangeBounds.length
      }
    }
    if (ascending) {
      partition
    } else {
      rangeBounds.length - partition
    }
  }

  override def equals(other: Any): Boolean = other match {
    case r: OrderedPartitioner[_, _] => r.rangeBounds.sameElements(rangeBounds) && r.ascending == ascending
    case _ => false
  }

  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    var i = 0
    while (i < rangeBounds.length) {
      result = prime * result + rangeBounds(i).hashCode
      i += 1
    }
    result = prime * result + ascending.hashCode
    result
  }

  def mapMonotonic[K2](newF: (K2) => T)(implicit k2Ord: Ordering[K2], k2ct: ClassTag[K2]): OrderedPartitioner[T, K2] = {
    new OrderedPartitioner[T, K2](rangeBounds, newF, ascending)
  }
}

object OrderedPartitioner {
  def empty[T, K](projectKey: (K) => T)(implicit tOrd: Ordering[T], kOrd: Ordering[K], tct: ClassTag[T],
    kct: ClassTag[K]): OrderedPartitioner[T, K] = new OrderedPartitioner(Array.empty[T], projectKey)

  /**
    * Copied from:
    *   org.apache.spark.RangePartitioner
    *   version 1.5.0
    *
    * Sketches the input RDD via reservoir sampling on each partition.
    *
    * @param rdd the input RDD to sketch
    * @param sampleSizePerPartition max sample size per partition
    * @return (total number of items, an array of (partitionId, number of items, sample))
    */
  def sketch[K: ClassTag](
    rdd: RDD[K],
    sampleSizePerPartition: Int): (Long, Array[(Int, Int, Array[K])]) = {
    val shift = rdd.id
    // val classTagK = classTag[K] // to avoid serializing the entire partitioner object
    val sketched = rdd.mapPartitionsWithIndex { (idx, iter) =>
      val seed = byteswap32(idx ^ (shift << 16))
      val (sample, n) = reservoirSampleAndCount(
        iter, sampleSizePerPartition, seed)
      Iterator((idx, n, sample))
    }.collect()
    val numItems = sketched.map(_._2.toLong).sum
    (numItems, sketched)
  }

  /**
    * Copied from:
    *   org.apache.spark.RangePartitioner
    *   version 1.5.0
    *
    * Determines the bounds for range partitioning from candidates with weights indicating how many
    * items each represents. Usually this is 1 over the probability used to sample this candidate.
    *
    * @param candidates unordered candidates with weights
    * @param partitions number of partitions
    * @return selected bounds
    */
  def determineBounds[K: Ordering : ClassTag](
    candidates: mutable.ArrayBuffer[(K, Float)],
    partitions: Int): Array[K] = {
    val ordering = implicitly[Ordering[K]]
    val ordered = candidates.sortBy(_._1)
    val numCandidates = ordered.size
    val sumWeights = ordered.map(_._2.toDouble).sum
    val step = sumWeights / partitions
    var cumWeight = 0.0
    var target = step
    val bounds = mutable.ArrayBuffer.empty[K]
    var i = 0
    var j = 0
    var previousBound = Option.empty[K]
    while ((i < numCandidates) && (j < partitions - 1)) {
      val (key, weight) = ordered(i)
      cumWeight += weight
      if (cumWeight > target) {
        // Skip duplicate values.
        if (previousBound.isEmpty || ordering.gt(key, previousBound.get)) {
          bounds += key
          target += step
          j += 1
          previousBound = Some(key)
        }
      }
      i += 1
    }
    bounds.toArray
  }

  /**
    * Copied from:
    *   org.apache.spark.util.random.SamplingUtils
    *   version 1.5.0
    *
    * Reservoir sampling implementation that also returns the input size.
    *
    * @param input input size
    * @param k reservoir size
    * @param seed random seed
    * @return (samples, input size)
    */
  def reservoirSampleAndCount[T: ClassTag](
    input: Iterator[T],
    k: Int,
    seed: Long = Random.nextLong()): (Array[T], Int) = {
    val reservoir = new Array[T](k)
    // Put the first k elements in the reservoir.
    var i = 0
    while (i < k && input.hasNext) {
      val item = input.next()
      reservoir(i) = item
      i += 1
    }

    // If we have consumed all the elements, return them. Otherwise do the replacement.
    if (i < k) {
      // If input size < k, trim the array to return only an array of input size.
      val trimReservoir = new Array[T](i)
      System.arraycopy(reservoir, 0, trimReservoir, 0, i)
      (trimReservoir, i)
    } else {
      // If input size > k, continue the sampling process.
      val rand = new XORShiftRandom(seed)
      while (input.hasNext) {
        val item = input.next()
        val replacementIndex = rand.nextInt(i)
        if (replacementIndex < k) {
          reservoir(replacementIndex) = item
        }
        i += 1
      }
      (reservoir, i)
    }
  }

  /**
    * Copied from:
    *   org.apache.spark.util.random.XORShiftRandom
    *   version 1.5.0
    *
    * Hash seeds to have 0 / 1 bits throughout.
    **/

  private def hashSeed(seed: Long): Long = {
    val bytes = ByteBuffer.allocate(java.lang.Long.SIZE).putLong(seed).array()
    MurmurHash3.bytesHash(bytes)
  }

  /**
    * Copied from:
    *   org.apache.spark.util.random.XORShiftRandom
    *   version 1.5.0
    */
  class XORShiftRandom(init: Long) extends JavaRandom(init) {

    def this() = this(System.nanoTime)

    private var seed = hashSeed(init)

    // we need to just override next - this will be called by nextInt, nextDouble,
    // nextGaussian, nextLong, etc.
    override protected def next(bits: Int): Int = {
      var nextSeed = seed ^ (seed << 21)
      nextSeed ^= (nextSeed >>> 35)
      nextSeed ^= (nextSeed << 4)
      seed = nextSeed
      (nextSeed & ((1L << bits) - 1)).asInstanceOf[Int]
    }

    override def setSeed(s: Long) {
      seed = hashSeed(s)
    }
  }

  /**
    * Copied from:
    *   org.apache.spark.util.CollectionUtils
    *   version 1.5.0
    */
  def makeBinarySearch[K: Ordering : ClassTag]: (Array[K], K) => Int = {
    // For primitive keys, we can use the natural ordering. Otherwise, use the Ordering comparator.
    classTag[K] match {
      case ClassTag.Float =>
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[Float]], x.asInstanceOf[Float])
      case ClassTag.Double =>
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[Double]], x.asInstanceOf[Double])
      case ClassTag.Byte =>
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[Byte]], x.asInstanceOf[Byte])
      case ClassTag.Char =>
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[Char]], x.asInstanceOf[Char])
      case ClassTag.Short =>
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[Short]], x.asInstanceOf[Short])
      case ClassTag.Int =>
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[Int]], x.asInstanceOf[Int])
      case ClassTag.Long =>
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[Long]], x.asInstanceOf[Long])
      case _ =>
        val comparator = implicitly[Ordering[K]].asInstanceOf[java.util.Comparator[Any]]
        (l, x) => util.Arrays.binarySearch(l.asInstanceOf[Array[AnyRef]], x, comparator)
    }
  }
}