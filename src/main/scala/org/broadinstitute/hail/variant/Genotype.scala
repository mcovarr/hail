package org.broadinstitute.hail.variant

import java.util

import org.apache.commons.lang3.builder.HashCodeBuilder
import org.apache.commons.math3.distribution.BinomialDistribution
import org.apache.spark.sql.types._
import org.broadinstitute.hail.ByteIterator
import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.check.{Arbitrary, Gen}
import org.json4s._

import scala.collection.mutable
import scala.language.implicitConversions

object GenotypeType extends Enumeration {
  type GenotypeType = Value
  val HomRef = Value(0)
  val Het = Value(1)
  val HomVar = Value(2)
  val NoCall = Value(-1)
}

import org.broadinstitute.hail.variant.GenotypeType.GenotypeType

object GTPair {
  def apply(j: Int, k: Int): GTPair = {
    require(j >= 0 && j <= 0xffff, s"GTPair invalid j value $j")
    require(k >= 0 && k <= 0xffff, s"GTPair invalid k value $k")
    new GTPair(j | (k << 16))
  }
}

class GTPair(val p: Int) extends AnyVal {
  def j: Int = p & 0xffff

  def k: Int = (p >> 16) & 0xffff

  def nNonRefAlleles: Int =
    (if (j != 0) 1 else 0) + (if (k != 0) 1 else 0)
}

case class CompleteGenotype(gt: Int, ad: Array[Int], dp: Int, gq: Int, pl: Array[Int])

class Genotype(private val _gt: Int,
  private val _ad: Array[Int],
  private val _dp: Int,
  private val _gq: Int,
  private val _px: Array[Int],
  val fakeRef: Boolean,
  val isDosage: Boolean) extends Serializable {

  require(_gt >= -1, s"invalid _gt value: ${ _gt }")
  require(_dp >= -1, s"invalid _dp value: ${ _dp }")

  if (isDosage) {
    require(_gq == -1)
    if (_px == null)
      require(_gt == -1)
    else {
      require(_px.sum == 32768)
      require(_gt == Genotype.gtFromLinear(_px).getOrElse(-1))
    }
  }

  def check(nAlleles: Int) {
    val nGenotypes = triangle(nAlleles)
    assert(gt.forall(i => i >= 0 && i < nGenotypes))
    assert(ad.forall(a => a.length == nAlleles))
    assert(px.forall(a => a.length == nGenotypes))
  }

  def copy(gt: Option[Int] = this.gt,
    ad: Option[Array[Int]] = this.ad,
    dp: Option[Int] = this.dp,
    gq: Option[Int] = this.gq,
    px: Option[Array[Int]] = this.px,
    fakeRef: Boolean = this.fakeRef,
    isDosage: Boolean = this.isDosage): Genotype = Genotype(gt, ad, dp, gq, px, fakeRef, isDosage)

  def toCompleteGenotype: Option[CompleteGenotype] = gt.flatMap { gt =>
    ad.flatMap { ad =>
      dp.flatMap { dp =>
        gq.flatMap { gq =>
          pl.flatMap { pl =>
            Some(CompleteGenotype(gt, ad, dp, gq, pl))
          }
        }
      }
    }
  }

  override def equals(that: Any): Boolean = that match {
    case g: Genotype =>
      _gt == g._gt &&
        ((_ad == null && g._ad == null)
          || (_ad != null && g._ad != null && _ad.sameElements(g._ad))) &&
        _dp == g._dp &&
        _gq == g._gq &&
        fakeRef == g.fakeRef &&
        isDosage == g.isDosage &&
        ((_px == null && g._px == null)
          || (_px != null && g._px != null && _px.sameElements(g._px)))

    case _ => false
  }

  override def hashCode: Int =
    new HashCodeBuilder(43, 19)
      .append(_gt)
      .append(util.Arrays.hashCode(_ad))
      .append(_dp)
      .append(_gq)
      .append(util.Arrays.hashCode(_px))
      .append(fakeRef)
      .append(isDosage)
      .toHashCode

  def gt: Option[Int] =
    if (_gt >= 0)
      Some(_gt)
    else
      None

  def ad: Option[Array[Int]] = Option(_ad)

  def dp: Option[Int] =
    if (_dp >= 0)
      Some(_dp)
    else
      None

  def od: Option[Int] =
    if (_dp >= 0 && _ad != null)
      Some(_dp - _ad.sum)
    else
      None

  def gq: Option[Int] =
    if (_gq >= 0)
      Some(_gq)
    else
      None

  def px: Option[Array[Int]] = {
    if (_px != null)
      Option(_px)
    else
      None
  }

  def pl: Option[Array[Int]] = {
    if (_px == null)
      None
    else if (!isDosage)
      Option(_px)
    else
      Option(Genotype.linearToPhred(_px))
  }

  def dosage: Option[Array[Double]] = {
    if (_px == null)
      None
    else if (isDosage)
      Option(_px.map(_ / 32768.0))
    else
      Option(Genotype.phredToDosage(_px))
  }

  def isHomRef: Boolean = Genotype.isHomRef(_gt)

  def isHet: Boolean = Genotype.isHet(_gt)

  def isHomVar: Boolean = Genotype.isHomVar(_gt)

  def isCalledNonRef: Boolean = Genotype.isCalledNonRef(_gt)

  def isHetNonRef: Boolean = Genotype.isHetNonRef(_gt)

  def isHetRef: Boolean = Genotype.isHetRef(_gt)

  def isNotCalled: Boolean = Genotype.isNotCalled(_gt)

  def isCalled: Boolean = Genotype.isCalled(_gt)

  def gtType: GenotypeType = Genotype.gtType(_gt)

  def nNonRefAlleles: Option[Int] = Genotype.nNonRefAlleles(_gt)

  def oneHotAlleles(v: Variant): Option[Array[Int]] = {
    gt.map { call =>
      val gtPair = Genotype.gtPair(call)
      val a = Array.fill(v.nAlleles)(0)
      a(gtPair.j) += 1
      a(gtPair.k) += 1
      a
    }
  }

  override def toString: String = {
    val b = new StringBuilder

    b.append(gt.map { gt =>
      val p = Genotype.gtPair(gt)
      s"${ p.j }/${ p.k }"
    }.getOrElse("./."))
    b += ':'
    b.append(ad.map(_.mkString(",")).getOrElse("."))
    b += ':'
    b.append(dp.map(_.toString).getOrElse("."))
    b += ':'
    b.append(gq.map(_.toString).getOrElse("."))
    b += ':'
    if (!isDosage)
      b.append(pl.map(_.mkString(",")).getOrElse("."))
    else
      b.append(dosage.map(_.mkString(",")).getOrElse("."))

    val regex = """(?::\.)+$""".r
    val regexMatch = regex.findFirstMatchIn(b)
    regexMatch.foreach { m =>
      b.delete(m.start, m.end)
    }

    b.result()
  }

  def pAB(theta: Double = 0.5): Option[Double] = ad.map { case Array(refDepth, altDepth) =>
    val d = new BinomialDistribution(refDepth + altDepth, theta)
    val minDepth = refDepth.min(altDepth)
    val minp = d.probability(minDepth)
    val mincp = d.cumulativeProbability(minDepth)
    (2 * mincp - minp).min(1.0).max(0.0)
  }

  def fractionReadsRef(): Option[Double] = ad.flatMap {
    arr => divOption(arr(0), arr.sum)
  }

  def toJSON: JValue = JObject(
    ("gt", gt.map(JInt(_)).getOrElse(JNull)),
    ("ad", ad.map(ads => JArray(ads.map(JInt(_)).toList)).getOrElse(JNull)),
    ("dp", dp.map(JInt(_)).getOrElse(JNull)),
    ("gq", gq.map(JInt(_)).getOrElse(JNull)),
    ("px", px.map(pxs => JArray(pxs.map(JInt(_)).toList)).getOrElse(JNull)),
    ("fakeRef", JBool(fakeRef)),
    ("isDosage", JBool(isDosage)))
}

object Genotype {
  def apply(gtx: Int): Genotype = new Genotype(gtx, null, -1, -1, null, false, false)

  def apply(gt: Option[Int] = None,
    ad: Option[Array[Int]] = None,
    dp: Option[Int] = None,
    gq: Option[Int] = None,
    px: Option[Array[Int]] = None,
    fakeRef: Boolean = false,
    isDosage: Boolean = false): Genotype = {
    new Genotype(gt.getOrElse(-1), ad.map(_.toArray).orNull, dp.getOrElse(-1), gq.getOrElse(-1), px.map(_.toArray).orNull, fakeRef, isDosage)
  }

  def schema: DataType = StructType(Array(
    StructField("gt", IntegerType),
    StructField("ad", ArrayType(IntegerType)),
    StructField("dp", IntegerType),
    StructField("gq", IntegerType),
    StructField("px", ArrayType(IntegerType)),
    StructField("fakeRef", BooleanType),
    StructField("isDosage", BooleanType)))

  final val flagMultiHasGTBit = 0x1
  final val flagMultiGTRefBit = 0x2
  final val flagBiGTMask = 0x3
  final val flagHasADBit = 0x4
  final val flagHasDPBit = 0x8
  final val flagHasGQBit = 0x10
  final val flagHasPLBit = 0x20
  final val flagSimpleADBit = 0x40
  final val flagSimpleDPBit = 0x80
  final val flagSimpleGQBit = 0x100
  final val flagFakeRefBit = 0x200

  def flagHasGT(isBiallelic: Boolean, flags: Int) =
    if (isBiallelic)
      (flags & flagBiGTMask) != 0
    else
      (flags & flagMultiHasGTBit) != 0

  def flagStoresGT(isBiallelic: Boolean, flags: Int) =
    isBiallelic || ((flags & flagMultiGTRefBit) != 0)

  def flagGT(isBiallelic: Boolean, flags: Int) = {
    assert(flagStoresGT(isBiallelic, flags))
    if (isBiallelic)
      (flags & flagBiGTMask) - 1
    else {
      assert((flags & flagMultiGTRefBit) != 0)
      0
    }
  }

  def flagSetGT(isBiallelic: Boolean, flags: Int, gt: Int): Int = {
    if (isBiallelic) {
      assert(gt >= 0 && gt <= 2)
      flags | ((gt & flagBiGTMask) + 1)
    } else {
      if (gt == 0)
        flags | flagMultiHasGTBit | flagMultiGTRefBit
      else
        flags | flagMultiHasGTBit
    }
  }

  def flagHasAD(flags: Int): Boolean = (flags & flagHasADBit) != 0

  def flagHasDP(flags: Int): Boolean = (flags & flagHasDPBit) != 0

  def flagHasGQ(flags: Int): Boolean = (flags & flagHasGQBit) != 0

  def flagHasPX(flags: Int): Boolean = (flags & flagHasPLBit) != 0

  def flagSetHasAD(flags: Int): Int = flags | flagHasADBit

  def flagSetHasDP(flags: Int): Int = flags | flagHasDPBit

  def flagSetHasGQ(flags: Int): Int = flags | flagHasGQBit

  def flagSetHasPX(flags: Int): Int = flags | flagHasPLBit

  def flagSimpleAD(flags: Int): Boolean = (flags & flagSimpleADBit) != 0

  def flagSimpleDP(flags: Int): Boolean = (flags & flagSimpleDPBit) != 0

  def flagSimpleGQ(flags: Int): Boolean = (flags & flagSimpleGQBit) != 0

  def flagSetSimpleAD(flags: Int): Int = flags | flagSimpleADBit

  def flagSetSimpleDP(flags: Int): Int = flags | flagSimpleDPBit

  def flagSetSimpleGQ(flags: Int): Int = flags | flagSimpleGQBit

  def flagFakeRef(flags: Int): Boolean = (flags & flagFakeRefBit) != 0

  def flagSetFakeRef(flags: Int): Int = flags | flagFakeRefBit

  def flagUnsetFakeRef(flags: Int): Int = flags ^ flagFakeRefBit

  def gqFromPL(pl: Array[Int]): Int = {
    var m = 99
    var m2 = 99
    var i = 0
    while (i < pl.length) {
      if (pl(i) < m) {
        m2 = m
        m = pl(i)
      } else if (pl(i) < m2)
        m2 = pl(i)
      i += 1
    }
    assert(m == 0, s"$m, $m2, [${ pl.mkString(",") }]")
    m2
  }

  def gtFromLinear(a: Array[Int]): Option[Int] = {
    def f(i: Int, m: Int, mi: Int, count: Int): Option[Int] = {
      if (i == a.length) {
        assert(count >= 1)
        if (count == 1)
          Some(mi)
        else
          None
      } else if (a(i) > m)
        f(i + 1, a(i), i, 1)
      else if (a(i) == m)
        f(i + 1, m, mi, count + 1)
      else
        f(i + 1, m, mi, count)
    }
    f(1, 0, a(0), 1)
  }

  def weightsToLinear[T: Numeric](a: Array[T]): Array[Int] = {
    import scala.math.Numeric.Implicits._

    val n = a.length
    val r = new Array[Int](a.length)
    val s = a.sum.toDouble
    assert(s >= 0)
    var aAcc = 0.0
    var rAcc = 0
    for (i <- 0 until n) {
      aAcc += a(i).toDouble
      val t = (aAcc * 32768 / s + 0.5).toInt
      r(i) = t - rAcc
      rAcc = t
    }
    assert(rAcc == 32768)
    r
  }

  def weightsToLinear(w0: Int, w1: Int, w2: Int): Array[Int] = {
    val sum = w0 + w1 + w2
    assert(sum > 0)

    val l0 = (w0.toDouble * 32768 / sum + 0.5).toInt
    val l1 = ((w0 + w1).toDouble * 32768 / sum + 0.5).toInt - l0
    val l2 = 32768 - l0 - l1
    Array(l0, l1, l2)
  }

  def weightsToLinear(w0: Double, w1: Double, w2: Double): Array[Int] = {
    val sum = w0 + w1 + w2
    assert(sum > 0)

    val l0 = (w0 * 32768 / sum + 0.5).toInt
    val l1 = ((w0 + w1) * 32768 / sum + 0.5).toInt - l0
    val l2 = 32768 - l0 - l1
    Array(l0, l1, l2)
  }

  lazy val phredConversionTable: Array[Double] = (0 to 65535).map { i => -10 * math.log10(if (i == 0) .25 else i) }.toArray

  def linearToPhred(a: Array[Int]): Array[Int] = {
    val x = a.map(phredConversionTable)
    x.map { d => (d - x.min + 0.5).toInt }
  }

  def phredToDosage(a: Array[Int]): Array[Double] = {
    val transformedProbs = a.map { i => math.pow(10, i / -10.0) }
    val s = transformedProbs.sum
    transformedProbs.map(_ / s)
  }

  def isHomRef(gt: Int): Boolean = gt == 0

  def isHet(gt: Int): Boolean = gt > 0 && {
    val p = Genotype.gtPair(gt)
    p.j != p.k
  }

  def isHomVar(gt: Int): Boolean = gt > 0 && {
    val p = Genotype.gtPair(gt)
    p.j == p.k
  }

  def isCalledNonRef(gt: Int): Boolean = gt > 0

  def isHetNonRef(gt: Int): Boolean = gt > 0 && {
    val p = Genotype.gtPair(gt)
    p.j > 0 && p.j != p.k
  }

  def isHetRef(gt: Int): Boolean = gt > 0 && {
    val p = Genotype.gtPair(gt)
    p.j == 0 && p.k > 0
  }

  def isNotCalled(gt: Int): Boolean = gt == -1

  def isCalled(gt: Int): Boolean = gt >= 0

  def gtType(gt: Int): GenotypeType =
    if (isHomRef(gt))
      GenotypeType.HomRef
    else if (isHet(gt))
      GenotypeType.Het
    else if (isHomVar(gt))
      GenotypeType.HomVar
    else {
      assert(isNotCalled(gt))
      GenotypeType.NoCall
    }

  def nNonRefAlleles(gt: Int): Option[Int] =
    if (gt >= 0)
      Some(Genotype.gtPair(gt).nNonRefAlleles)
    else
      None

  val smallGTPair = Array(GTPair(0, 0), GTPair(0, 1), GTPair(1, 1),
    GTPair(0, 2), GTPair(1, 2), GTPair(2, 2),
    GTPair(0, 3), GTPair(1, 3), GTPair(2, 3), GTPair(3, 3),
    GTPair(0, 4), GTPair(1, 4), GTPair(2, 4), GTPair(3, 4), GTPair(4, 4),
    GTPair(0, 5), GTPair(1, 5), GTPair(2, 5), GTPair(3, 5), GTPair(4, 5), GTPair(5, 5),
    GTPair(0, 6), GTPair(1, 6), GTPair(2, 6), GTPair(3, 6), GTPair(4, 6), GTPair(5, 6),
    GTPair(6, 6),
    GTPair(0, 7), GTPair(1, 7), GTPair(2, 7), GTPair(3, 7), GTPair(4, 7),
    GTPair(5, 7), GTPair(6, 7), GTPair(7, 7))

  def gtPairRecursive(i: Int): GTPair = {
    def f(j: Int, k: Int): GTPair = if (j <= k)
      GTPair(j, k)
    else
      f(j - k - 1, k + 1)

    f(i, 0)
  }

  def gtPairSqrt(i: Int): GTPair = {
    val k: Int = (Math.sqrt(8 * i.toDouble + 1) / 2 - 0.5).toInt
    assert(k * (k + 1) / 2 <= i)
    val j = i - k * (k + 1) / 2
    assert(gtIndex(j, k) == i)
    GTPair(j, k)
  }

  def gtPair(i: Int): GTPair = {
    if (i < smallGTPair.length)
      smallGTPair(i)
    else
      gtPairSqrt(i)
  }

  def gtIndex(j: Int, k: Int): Int = {
    require(j >= 0 && j <= k, s"invalid gtIndex: ($j, $k)")
    k * (k + 1) / 2 + j
  }

  def gtIndex(p: GTPair): Int = gtIndex(p.j, p.k)

  def read(nAlleles: Int, isDosage: Boolean, a: ByteIterator): Genotype = {
    val isBiallelic = nAlleles == 2

    val flags = a.readULEB128()

    val gt: Int =
      if (flagHasGT(isBiallelic, flags)) {
        if (flagStoresGT(isBiallelic, flags))
          flagGT(isBiallelic, flags)
        else
          a.readULEB128()
      } else
        -1

    val ad: Array[Int] =
      if (flagHasAD(flags)) {
        val ada = new Array[Int](nAlleles)
        if (flagSimpleAD(flags)) {
          assert(gt >= 0)
          val p = Genotype.gtPair(gt)
          ada(p.j) = a.readULEB128()
          if (p.j != p.k)
            ada(p.k) = a.readULEB128()
        } else {
          for (i <- ada.indices)
            ada(i) = a.readULEB128()
        }
        ada
      } else
        null

    val dp =
      if (flagHasDP(flags)) {
        if (flagHasAD(flags)) {
          if (flagSimpleDP(flags))
            ad.sum
          else
            ad.sum + a.readULEB128()
        } else
          a.readULEB128()
      } else
        -1 // None

    val px: Array[Int] =
      if (flagHasPX(flags)) {
        val pxa = new Array[Int](triangle(nAlleles))
        if (gt >= 0) {
          var i = 0
          while (i < gt) {
            pxa(i) = a.readULEB128()
            i += 1
          }
          i += 1
          while (i < pxa.length) {
            pxa(i) = a.readULEB128()
            i += 1
          }

          if (isDosage)
            pxa(gt) = 32768 - pxa.sum // original values summed to 32768 or 1.0 in probability

        } else {
          var i = 0
          while (i < pxa.length) {
            pxa(i) = a.readULEB128()
            i += 1
          }
        }

        pxa
      } else
        null

    val gq: Int =
      if (flagHasGQ(flags)) {
        if (flagSimpleGQ(flags))
          gqFromPL(px)
        else
          a.readULEB128()
      } else
        -1

    new Genotype(gt, ad, dp, gq, px, flagFakeRef(flags), isDosage)
  }

  def gtIndexWithSwap(i: Int, j: Int): Int = {
    if (j < i)
      gtIndex(j, i)
    else
      gtIndex(i, j)
  }

  def genDosage(nAlleles: Int): Gen[Genotype] = {
    val nGenotypes = triangle(nAlleles)
    // FIXME hack, add Gen.partition(nParts) and Gen.partitionN(nParts, size)
    for (px <- Gen.option(Gen.partition(nGenotypes).resize(32768))) yield {
      val gt = px.flatMap(gtFromLinear)
      val g = Genotype(gt = gt, px = px, isDosage = true)
      g.check(nAlleles)
      g
    }
  }

  def genExtreme(nAlleles: Int): Gen[Genotype] = {
    val m = Int.MaxValue / (nAlleles + 1)
    val nGenotypes = triangle(nAlleles)
    for (gt: Option[Int] <- Gen.option(Gen.choose(0, nGenotypes - 1));
      ad <- Gen.option(Gen.buildableOfN[Array, Int](nAlleles,
        Gen.choose(0, m)));
      dp <- Gen.option(Gen.choose(0, m));
      gq <- Gen.option(Gen.choose(0, 10000));
      pl <- Gen.oneOfGen(
        Gen.option(Gen.buildableOfN[Array, Int](nGenotypes, Gen.choose(0, m))),
        Gen.option(Gen.buildableOfN[Array, Int](nGenotypes, Gen.choose(0, 100))))) yield {
      gt.foreach { gtx =>
        pl.foreach { pla => pla(gtx) = 0 }
      }
      pl.foreach { pla =>
        val m = pla.min
        var i = 0
        while (i < pla.length) {
          pla(i) -= m
          i += 1
        }
      }
      val g = Genotype(gt, ad,
        dp.map(_ + ad.map(_.sum).getOrElse(0)), gq, pl)
      g.check(nAlleles)
      g
    }
  }

  def genRealistic(nAlleles: Int): Gen[Genotype] = {
    val nGenotypes = triangle(nAlleles)
    for (callRate <- Gen.choose(0d, 1d);
      alleleFrequencies <- Gen.buildableOfN[Array, Double](nAlleles, Gen.choose(1e-6, 1d)) // avoid divison by 0
        .map { rawWeights =>
        val sum = rawWeights.sum
        rawWeights.map(_ / sum)
      };
      gt <- Gen.option(Gen.zip(Gen.chooseWithWeights(alleleFrequencies), Gen.chooseWithWeights(alleleFrequencies))
        .map { case (gti, gtj) => gtIndexWithSwap(gti, gtj) }, callRate);
      ad <- Gen.option(Gen.buildableOfN[Array, Int](nAlleles,
        Gen.choose(0, 50)));
      dp <- Gen.choose(0, 30).map(d => ad.map(o => o.sum + d));
      pl <- Gen.option(Gen.buildableOfN[Array, Int](nGenotypes, Gen.choose(0, 1000)).map { arr =>
        gt match {
          case Some(i) =>
            arr(i) = 0
            arr
          case None =>
            val min = arr.min
            arr.map(_ - min)
        }
      });
      gq <- Gen.choose(-30, 30).map(i => pl.map(pls => math.max(0, gqFromPL(pls) + i)))
    ) yield
      Genotype(gt, ad, dp, gq, pl)
  }

  def genVariantGenotype: Gen[(Variant, Genotype)] =
    for (v <- Variant.gen;
      g <- Gen.oneOfGen(genExtreme(v.nAlleles), genRealistic(v.nAlleles), genDosage(v.nAlleles)))
      yield (v, g)

  def genArb: Gen[Genotype] =
    for (v <- Variant.gen;
      g <- Gen.oneOfGen(genExtreme(v.nAlleles), genRealistic(v.nAlleles), genDosage(v.nAlleles)))
      yield g

  implicit def arbGenotype = Arbitrary(genArb)
}

class GenotypeBuilder(nAlleles: Int, isDosage: Boolean = false) {
  require(nAlleles > 0, s"tried to create genotype builder with $nAlleles ${ plural(nAlleles, "allele") }")
  val isBiallelic = nAlleles == 2
  val nGenotypes = triangle(nAlleles)

  var flags: Int = 0

  private var gt: Int = 0
  private var ad: Array[Int] = _
  private var dp: Int = 0
  private var gq: Int = 0
  private var px: Array[Int] = _

  def clear() {
    flags = 0
  }

  def hasGT: Boolean =
    Genotype.flagHasGT(isBiallelic, flags)

  def setGT(newGT: Int) {
    if (newGT < 0)
      fatal(s"invalid GT value `$newGT': negative value")
    if (newGT > nGenotypes)
      fatal(s"invalid GT value `$newGT': value larger than maximum number of genotypes $nGenotypes")
    if (hasGT)
      fatal(s"invalid GT, genotype already had GT")
    flags = Genotype.flagSetGT(isBiallelic, flags, newGT)
    gt = newGT
  }

  def setAD(newAD: Array[Int]) {
    if (newAD.length != nAlleles)
      fatal(s"invalid AD field `${ newAD.mkString(",") }': expected $nAlleles values, but got ${ newAD.length }.")
    flags = Genotype.flagSetHasAD(flags)
    ad = newAD
  }

  def setDP(newDP: Int) {
    if (newDP < 0)
      fatal(s"invalid DP field `$newDP': negative value")
    flags = Genotype.flagSetHasDP(flags)
    dp = newDP
  }

  def setGQ(newGQ: Int) {
    if (newGQ < 0)
      fatal(s"invalid GQ field `$newGQ': negative value")
    flags = Genotype.flagSetHasGQ(flags)
    gq = newGQ
  }

  def setPX(newPX: Array[Int]) {
    if (newPX.length != nGenotypes)
      fatal(s"invalid PL field `${ newPX.mkString(",") }': expected $nGenotypes values, but got ${ newPX.length }.")
    flags = Genotype.flagSetHasPX(flags)
    px = newPX
  }

  def setFakeRef() {
    flags = Genotype.flagSetFakeRef(flags)
  }

  def set(g: Genotype) {
    g.gt.foreach(setGT)
    g.ad.foreach(setAD)
    g.dp.foreach(setDP)
    g.gq.foreach(setGQ)
    g.px.foreach(setPX)

    if (g.fakeRef)
      setFakeRef()
  }

  def write(b: mutable.ArrayBuilder[Byte]) {
    val hasGT = Genotype.flagHasGT(isBiallelic, flags)
    val hasAD = Genotype.flagHasAD(flags)
    val hasDP = Genotype.flagHasDP(flags)
    val hasGQ = Genotype.flagHasGQ(flags)
    val hasPX = Genotype.flagHasPX(flags)

    if (isDosage) {
      if (hasPX) {
        Genotype.gtFromLinear(px) match {
          case Some(gt2) => assert(hasGT && gt == gt2)
          case None => assert(!hasGT)
        }
      } else
        assert(!hasGT)
    }

    var j = 0
    var k = 0
    if (hasGT) {
      val p = Genotype.gtPair(gt)
      j = p.j
      k = p.k
      if (hasAD) {
        var i = 0
        var simple = true
        while (i < ad.length && simple) {
          if (i != j && i != k && ad(i) != 0)
            simple = false
          i += 1
        }
        if (simple)
          flags = Genotype.flagSetSimpleAD(flags)
      }
    }

    var adsum = 0
    if (hasAD && hasDP) {
      adsum = ad.sum
      if (adsum == dp)
        flags = Genotype.flagSetSimpleDP(flags)
    }

    if (hasPX && hasGQ) {
      val gqFromPL = Genotype.gqFromPL(px)
      if (gq == gqFromPL)
        flags = Genotype.flagSetSimpleGQ(flags)
    }

    /*
    println("flags:")
    if (Genotype.flagHasGT(isBiallelic, flags))
      println(s"  gt = $gt")
    if (Genotype.flagHasDP(flags))
      println(s"  dp = $dp")
    */

    b.writeULEB128(flags)

    if (hasGT && !Genotype.flagStoresGT(isBiallelic, flags))
      b.writeULEB128(gt)

    if (hasAD) {
      if (Genotype.flagSimpleAD(flags)) {
        b.writeULEB128(ad(j))
        if (k != j)
          b.writeULEB128(ad(k))
      } else
        ad.foreach(b.writeULEB128)
    }

    if (hasDP) {
      if (hasAD) {
        if (!Genotype.flagSimpleDP(flags))
          b.writeULEB128(dp - adsum)
      } else
        b.writeULEB128(dp)
    }

    if (hasPX) {
      if (hasGT) {
        var i = 0
        while (i < gt) {
          b.writeULEB128(px(i))
          i += 1
        }
        i += 1
        while (i < px.length) {
          b.writeULEB128(px(i))
          i += 1
        }
      } else
        px.foreach(b.writeULEB128)
    }

    if (hasGQ) {
      if (!Genotype.flagSimpleGQ(flags))
        b.writeULEB128(gq)
    }
  }
}
