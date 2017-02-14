package is.hail.io

import is.hail.SparkSuite
import is.hail.annotations.Annotation
import is.hail.check.Prop._
import is.hail.check.Properties
import is.hail.utils._
import is.hail.variant.{Genotype, VSMSubgen, Variant, VariantDataset, VariantSampleMatrix}
import org.testng.annotations.Test

class SplitSuite extends SparkSuite {

  object Spec extends Properties("MultiSplit") {
    property("fakeRef implies wasSplit") =
      forAll(VariantSampleMatrix.gen[Genotype](hc, VSMSubgen.random).map(_.splitMulti())) { (vds: VariantDataset) =>
        val wasSplitQuerier = vds.vaSignature.query("wasSplit")
        vds.mapWithAll((v: Variant, va: Annotation, _: String, _: Annotation, g: Genotype) =>
          !g.fakeRef || wasSplitQuerier(va).asInstanceOf[Option[Boolean]].get)
          .collect()
          .forall(identity)
      }
  }

  @Test def splitTest() {
    Spec.check()

    val vds1 = hc.importVCF("src/test/resources/split_test.vcf")
      .splitMulti()

    val vds2 = hc.importVCF("src/test/resources/split_test_b.vcf")

    // test splitting and downcoding
    vds1.mapWithKeys((v, s, g) => ((v, s), g.copy(fakeRef = false)))
      .join(vds2.mapWithKeys((v, s, g) => ((v, s), g)))
      .foreach { case (k, (g1, g2)) =>
        if (g1 != g2)
          println(s"$g1, $g2")
        simpleAssert(g1 == g2)
      }

    val wasSplitQuerier = vds1.vaSignature.query("wasSplit")

    // test for wasSplit
    vds1.mapWithAll((v, va, s, sa, g) => (v.start, wasSplitQuerier(va).asInstanceOf[Option[Boolean]].get))
      .foreach { case (i, b) =>
        simpleAssert(b == (i != 1180))
      }

    // test for fakeRef
    assert(vds1.mapWithKeys((v, s, g) => ((v.start, v.alt, s), g.fakeRef)).filter(_._2).map(_._1.toString).collect.toSet
      == Set("(2167,AAAAC,HG00097)", "(2167,A,HG00100)", "(2167,AAAACAAAC,HG00103)", "(1183,C,HG00100)", "(2167,A,HG00103)", "(2167,AAAAC,HG00099)",
      "(2167,AAAAC,HG00104)", "(2167,AAAACAAAC,HG00104)", "(1783,TA,HG00100)", "(2167,A,HG00101)", "(2167,A,HG00099)", "(1183,C,HG00103)",
      "(2167,AAAAC,HG00103)", "(1183,C,HG00104)", "(1783,TA,HG00101)", "(2167,AAAACAAAC,HG00101)", "(2167,AAAAC,HG00101)", "(1783,T,HG00099)",
      "(2167,A,HG00097)", "(2167,AAAAC,HG00102)", "(2167,AAAACAAAC,HG00100)", "(1783,T,HG00101)", "(1783,TA,HG00102)", "(1783,T,HG00097)",
      "(2167,AAAACAAAC,HG00102)"))
  }
}
