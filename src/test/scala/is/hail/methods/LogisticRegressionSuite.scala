package is.hail.methods

import is.hail.SparkSuite
import is.hail.utils._
import is.hail.annotations.Querier
import is.hail.driver._
import is.hail.expr.{TBoolean, TDouble}
import is.hail.variant.Variant
import org.testng.annotations.Test

class LogisticRegressionSuite extends SparkSuite {

  @Test def waldTestWithTwoCov() {
    val vds = hc.importVCF("src/test/resources/regressionLogistic.vcf")
      .splitMulti()
      .annotateSamplesTable("src/test/resources/regressionLogistic.cov",
        "Sample",
        root = Some("sa.cov"),
        config = TextTableConfiguration(types = Map("Cov1" -> TDouble, "Cov2" -> TDouble)))
      .annotateSamplesTable("src/test/resources/regressionLogisticBoolean.pheno",
        "Sample",
        root = Some("sa.pheno"),
        config = TextTableConfiguration(types = Map("isCase" -> TBoolean), missing = "0"))
      .logreg("wald", "sa.pheno.isCase", Array("sa.cov.Cov1", "sa.cov.Cov2"), "va.logreg")

    val v1 = Variant("1", 1, "C", "T")
    // x = (0, 1, 0, 0, 0, 1, 0, 0, 0, 0)
    val v2 = Variant("1", 2, "C", "T")
    // x = (., 2, ., 2, 0, 0, 0, 0, 0, 0)
    val v3 = Variant("1", 3, "C", "T")
    // x = (0, ., 1, 1, 1, ., 0, 0, 0, 0)
    val v6 = Variant("1", 6, "C", "T")
    // x = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val v7 = Variant("1", 7, "C", "T")
    // x = (1, 1, 1, 1, 1, 1, 0, 0, 0, 0)
    val v8 = Variant("1", 8, "C", "T")
    // x = (2, 2, 2, 2, 2, 2, 0, 0, 0, 0)
    val v9 = Variant("1", 9, "C", "T")
    // x = (., 1, 1, 1, 1, 1, 0, 0, 0, 0)
    val v10 = Variant("1", 10, "C", "T") // x = (., 2, 2, 2, 2, 2, 0, 0, 0, 0)

    val qBeta = vds.queryVA("va.logreg.wald.beta")._2
    val qSe = vds.queryVA("va.logreg.wald.se")._2
    val qZstat = vds.queryVA("va.logreg.wald.zstat")._2
    val qPVal = vds.queryVA("va.logreg.wald.pval")._2
    val qConverged = vds.queryVA("va.logreg.fit.converged")._2
    val qExploded = vds.queryVA("va.logreg.fit.exploded")._2

    val annotationMap = vds.variantsAndAnnotations
      .collect()
      .toMap

    def assertDouble(q: Querier, v: Variant, value: Double) =
      assert(D_==(q(annotationMap(v)).get.asInstanceOf[Double], value))

    def assertExploded(v: Variant) = assert(qExploded(annotationMap(v)).get.asInstanceOf[Boolean])

    def assertNotConverged(v: Variant) = assert(!qConverged(annotationMap(v)).get.asInstanceOf[Boolean])

    def assertConstant(v: Variant) = assert(qConverged(annotationMap(v)).isEmpty)

    /*
    comparing to output of R code:
    x = c(0, 1, 0, 0, 0, 1, 0, 0, 0, 0)
    y = c(0, 0, 1, 1, 1, 1, 0, 0, 1, 1)
    c1 = c(0, 2, 1, -2, -2, 4, 1, 2, 3, 4)
    c2 = c(-1, 3, 5, 0, -4, 3, 0, -2, -1, -4)

    logfit <- glm(y ~ x + c1 + c2, family=binomial(link="logit"))
    waldtest <- coef(summary(logfit))
    beta <- waldtest["x", "Estimate"]
    se <- waldtest["x", "Std. Error"]
    zstat <- waldtest["x", "z value"]
    pval <- waldtest["x", "Pr(>|z|)"]
    */

    assertDouble(qBeta, v1, -0.81226793796)
    assertDouble(qSe, v1, 2.1085483421)
    assertDouble(qZstat, v1, -0.3852261396)
    assertDouble(qPVal, v1, 0.7000698784)

    // v2 has two missing genotypes, comparing to output of R code as above with imputed genotypes:
    // x = c(.5, 2, .5, 2, 0, 0, 0, 0, 0, 0)

    assertDouble(qBeta, v2, -0.43659460858)
    assertDouble(qSe, v2, 1.0296902941)
    assertDouble(qZstat, v2, -0.4240057531)
    assertDouble(qPVal, v2, 0.6715616176)

    // seperable => does not converge
    // v3 has two missing genotypes, comparing to output of R code as above with imputed genotypes:
    // x = c(0, 0.375, 1, 1, 1, 0.375, 0, 0, 0, 0)

    // assertExploded(v3), explodes at iteration ~30
    assertNotConverged(v3)

    // constant genotypes after imputation
    assertConstant(v6)
    assertConstant(v7)
    assertConstant(v8)
    assertConstant(v9)
    assertConstant(v10)
  }

  @Test def lrTestWithTwoCov() {
    val vds = hc.importVCF("src/test/resources/regressionLogistic.vcf")
      .splitMulti()
      .annotateSamplesTable("src/test/resources/regressionLogistic.cov",
        "Sample",
        root = Some("sa.cov"),
        config = TextTableConfiguration(types = Map("Cov1" -> TDouble, "Cov2" -> TDouble)))
      .annotateSamplesTable("src/test/resources/regressionLogisticBoolean.pheno",
        "Sample",
        root = Some("sa.pheno"),
        config = TextTableConfiguration(types = Map("isCase" -> TBoolean), missing = "0"))
      .logreg("lrt", "sa.pheno.isCase", Array("sa.cov.Cov1", "sa.cov.Cov2"), "va.logreg")


    val v1 = Variant("1", 1, "C", "T")
    // x = (0, 1, 0, 0, 0, 1, 0, 0, 0, 0)
    val v2 = Variant("1", 2, "C", "T")
    // x = (., 2, ., 2, 0, 0, 0, 0, 0, 0)
    val v3 = Variant("1", 3, "C", "T")
    // x = (0, ., 1, 1, 1, ., 0, 0, 0, 0)
    val v6 = Variant("1", 6, "C", "T")
    // x = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val v7 = Variant("1", 7, "C", "T")
    // x = (1, 1, 1, 1, 1, 1, 0, 0, 0, 0)
    val v8 = Variant("1", 8, "C", "T")
    // x = (2, 2, 2, 2, 2, 2, 0, 0, 0, 0)
    val v9 = Variant("1", 9, "C", "T")
    // x = (., 1, 1, 1, 1, 1, 0, 0, 0, 0)
    val v10 = Variant("1", 10, "C", "T") // x = (., 2, 2, 2, 2, 2, 0, 0, 0, 0)

    val qBeta = vds.queryVA("va.logreg.lrt.beta")._2
    val qChi2 = vds.queryVA("va.logreg.lrt.chi2")._2
    val qPVal = vds.queryVA("va.logreg.lrt.pval")._2
    val qConverged = vds.queryVA("va.logreg.fit.converged")._2
    val qExploded = vds.queryVA("va.logreg.fit.exploded")._2

    val annotationMap = vds.variantsAndAnnotations
      .collect()
      .toMap

    def assertDouble(q: Querier, v: Variant, value: Double) =
      assert(D_==(q(annotationMap(v)).get.asInstanceOf[Double], value))

    def assertExploded(v: Variant) = assert(qExploded(annotationMap(v)).get.asInstanceOf[Boolean])

    def assertNotConverged(v: Variant) = assert(!qConverged(annotationMap(v)).get.asInstanceOf[Boolean])

    def assertConstant(v: Variant) = assert(qConverged(annotationMap(v)).isEmpty)

    /*
    comparing to output of R code:
    x = c(0, 1, 0, 0, 0, 1, 0, 0, 0, 0)
    y = c(0, 0, 1, 1, 1, 1, 0, 0, 1, 1)
    c1 = c(0, 2, 1, -2, -2, 4, 1, 2, 3, 4)
    c2 = c(-1, 3, 5, 0, -4, 3, 0, -2, -1, -4)

    logfit <- glm(y ~ x + c1 + c2, family=binomial(link="logit"))
    logfitnull <- glm(y ~ c1 + c2, family=binomial(link="logit"))
    beta <- coef(summary(logfit))["x", "Estimate"]
    lrtest <- anova(logfitnull, logfit, test="LRT")
    chi2 <- lrtest[["Deviance"]][2]
    pval <- lrtest[["Pr(>Chi)"]][2]
    */

    assertDouble(qBeta, v1, -0.81226793796)
    assertDouble(qChi2, v1, 0.1503349167)
    assertDouble(qPVal, v1, 0.6982155052)

    // v2 has two missing genotypes, comparing to output of R code as above with imputed genotypes:
    // x = c(.5, 2, .5, 2, 0, 0, 0, 0, 0, 0)

    assertDouble(qBeta, v2, -0.43659460858)
    assertDouble(qChi2, v2, 0.1813968574)
    assertDouble(qPVal, v2, 0.6701755415)

    // seperable => does not converge
    // v3 has two missing genotypes, comparing to output of R code as above with imputed genotypes:
    // x = c(0, 0.375, 1, 1, 1, 0.375, 0, 0, 0, 0)

    // assertExploded(v3), explodes at iteration ~30
    assertNotConverged(v3)

    // constant genotypes after imputation
    assertConstant(v6)
    assertConstant(v7)
    assertConstant(v8)
    assertConstant(v9)
    assertConstant(v10)
  }

  @Test def scoreTestWithTwoCov() {
    val vds = hc.importVCF("src/test/resources/regressionLogistic.vcf")
      .splitMulti()
      .annotateSamplesTable("src/test/resources/regressionLogistic.cov",
        "Sample",
        root = Some("sa.cov"),
        config = TextTableConfiguration(types = Map("Cov1" -> TDouble, "Cov2" -> TDouble)))
      .annotateSamplesTable("src/test/resources/regressionLogisticBoolean.pheno",
        "Sample",
        root = Some("sa.pheno"),
        config = TextTableConfiguration(types = Map("isCase" -> TBoolean), missing = "0"))
      .logreg("score", "sa.pheno.isCase", Array("sa.cov.Cov1", "sa.cov.Cov2"), "va.logreg")

    val v1 = Variant("1", 1, "C", "T")
    // x = (0, 1, 0, 0, 0, 1, 0, 0, 0, 0)
    val v2 = Variant("1", 2, "C", "T")
    // x = (., 2, ., 2, 0, 0, 0, 0, 0, 0)
    val v3 = Variant("1", 3, "C", "T")
    // x = (0, ., 1, 1, 1, ., 0, 0, 0, 0)
    val v6 = Variant("1", 6, "C", "T")
    // x = (0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val v7 = Variant("1", 7, "C", "T")
    // x = (1, 1, 1, 1, 1, 1, 0, 0, 0, 0)
    val v8 = Variant("1", 8, "C", "T")
    // x = (2, 2, 2, 2, 2, 2, 0, 0, 0, 0)
    val v9 = Variant("1", 9, "C", "T")
    // x = (., 1, 1, 1, 1, 1, 0, 0, 0, 0)
    val v10 = Variant("1", 10, "C", "T") // x = (., 2, 2, 2, 2, 2, 0, 0, 0, 0)

    val qChi2 = vds.queryVA("va.logreg.score.chi2")._2
    val qPVal = vds.queryVA("va.logreg.score.pval")._2

    val annotationMap = vds.variantsAndAnnotations
      .collect()
      .toMap

    def assertDouble(q: Querier, v: Variant, value: Double) =
      assert(D_==(q(annotationMap(v)).get.asInstanceOf[Double], value))

    def assertEmpty(v: Variant) = assert(qChi2(annotationMap(v)).isEmpty)

    /*
    comparing to output of R code:
    x = c(0, 1, 0, 0, 0, 1, 0, 0, 0, 0)
    y = c(0, 0, 1, 1, 1, 1, 0, 0, 1, 1)
    c1 = c(0, 2, 1, -2, -2, 4, 1, 2, 3, 4)
    c2 = c(-1, 3, 5, 0, -4, 3, 0, -2, -1, -4)

    logfit <- glm(y ~ c1 + c2 + x, family=binomial(link="logit"))
    logfitnull <- glm(y ~ c1 + c2, family=binomial(link="logit"))
    scoretest <- anova(logfitnull, logfit, test="Rao")
    chi2 <- scoretest[["Rao"]][2]
    pval <- scoretest[["Pr(>Chi)"]][2]
    */

    assertDouble(qChi2, v1, 0.1502364955)
    assertDouble(qPVal, v1, 0.6983094571)

    // v2 has two missing genotypes, comparing to output of R code as above with imputed genotypes:
    // x = c(.5, 2, .5, 2, 0, 0, 0, 0, 0, 0)

    assertDouble(qChi2, v2, 0.1823600965)
    assertDouble(qPVal, v2, 0.6693528073)

    // seperable => does not converge
    // v3 has two missing genotypes, comparing to output of R code as above with imputed genotypes:
    // x = c(0, 0.375, 1, 1, 1, 0.375, 0, 0, 0, 0)

    assertDouble(qChi2, v3, 7.047367694)
    assertDouble(qPVal, v3, 0.007938182229)

    // constant genotypes after imputation
    assertEmpty(v6)
    assertEmpty(v7)
    assertEmpty(v8)
    assertEmpty(v9)
    assertEmpty(v10)
  }

  @Test def waldEpactsTest() {
    val vds = hc.importVCF("src/test/resources/regressionLogisticEpacts.vcf")
      .splitMulti()
      .annotateSamplesFam("src/test/resources/regressionLogisticEpacts.cov")
      .annotateSamplesTable("src/test/resources/regressionLogisticBoolean.pheno",
        "IND_ID",
        root = Some("sa.pc"),
        config = TextTableConfiguration(types = Map("PC1" -> TDouble, "PC2" -> TDouble), missing = "0"))
      .logreg("wald", "sa.fam.isCase", Array("sa.fam.isFemale", "sa.pc.PC1", "sa.pc.PC2"), "va.logreg")
      .logreg("lrt", "sa.fam.isCase", Array("sa.fam.isFemale", "sa.pc.PC1", "sa.pc.PC2"), "va.logreg2")
      .logreg("score", "sa.fam.isCase", Array("sa.fam.isFemale", "sa.pc.PC1", "sa.pc.PC2"), "va.logreg3")

    // 2535 samples from 1K Genomes Project
    val v1 = Variant("22", 16060511, "T", "TTC")
    // MAC  623
    val v2 = Variant("22", 16115878, "G", "C")
    // MAC  370
    val v3 = Variant("22", 16115882, "G", "T")
    // MAC 1207
    val v4 = Variant("22", 16117940, "T", "G")
    // MAC    7
    val v5 = Variant("22", 16117953, "G", "C") // MAC   21

    val qBeta = vds.queryVA("va.logreg.wald.beta")._2
    val qSe = vds.queryVA("va.logreg.wald.se")._2
    val qZstat = vds.queryVA("va.logreg.wald.zstat")._2
    val qPVal = vds.queryVA("va.logreg.wald.pval")._2
    val qPValLR = vds.queryVA("va.logreg2.lrt.pval")._2
    val qPValScore = vds.queryVA("va.logreg3.score.pval")._2

    val annotationMap = vds.variantsAndAnnotations
      .collect()
      .toMap

    def assertDouble(q: Querier, v: Variant, value: Double, tol: Double = 1e-4) =
      assert(D_==(q(annotationMap(v)).get.asInstanceOf[Double], value, tol))

    // Comparing to output of b.wald, b.lrt, and b.score in EPACTS
    // for five 1KG project variants with no missing genotypes
    // http://genome.sph.umich.edu/wiki/EPACTS#Single_Variant_Tests

    assertDouble(qBeta, v1, -0.097476)
    assertDouble(qSe, v1, 0.087478)
    assertDouble(qZstat, v1, -1.1143, 1e-3)
    assertDouble(qPVal, v1, 0.26516)
    assertDouble(qPValLR, v1, 0.26475)
    assertDouble(qPValScore, v1, 0.26499)

    assertDouble(qBeta, v2, -0.052632)
    assertDouble(qSe, v2, 0.11272)
    assertDouble(qZstat, v2, -0.46691)
    assertDouble(qPVal, v2, 0.64056)
    assertDouble(qPValLR, v2, 0.64046)
    assertDouble(qPValScore, v2, 0.64054)

    assertDouble(qBeta, v3, -0.15598)
    assertDouble(qSe, v3, 0.079508)
    assertDouble(qZstat, v3, -1.9619)
    assertDouble(qPVal, v3, 0.049779)
    assertDouble(qPValLR, v3, 0.049675)
    assertDouble(qPValScore, v3, 0.049717)

    assertDouble(qBeta, v4, -0.88059)
    assertDouble(qSe, v4, 0.83769, 1e-3)
    assertDouble(qZstat, v4, -1.0512, 1e-3)
    assertDouble(qPVal, v4, 0.29316, 1e-2)
    assertDouble(qPValLR, v4, 0.26984)
    assertDouble(qPValScore, v4, 0.27828)

    assertDouble(qBeta, v5, 0.54921)
    assertDouble(qSe, v5, 0.4517, 1e-3)
    assertDouble(qZstat, v5, 1.2159, 1e-3)
    assertDouble(qPVal, v5, 0.22403, 1e-3)
    assertDouble(qPValLR, v5, 0.21692)
    assertDouble(qPValScore, v5, 0.21849)
  }
}
