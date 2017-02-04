package is.hail

import java.util
import java.util.Properties

import org.apache.log4j.{LogManager, PropertyConfigurator}
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.{ProgressBarBuilder, SparkConf, SparkContext}
import is.hail.annotations.Annotation
import is.hail.expr._
import is.hail.keytable.KeyTable
import is.hail.utils._
import is.hail.variant._

import com.mongodb.spark._
import com.mongodb.spark.sql._

import scala.collection.JavaConverters._
import scala.collection.mutable

package object driver {

  def exportMongoDB(sqlContext: SQLContext, kt: KeyTable, mode: String = "append"): Unit = {
    MongoSpark.save(kt.toDF(sqlContext)
      .write
      .mode(mode))
  }

  def count(vds: VariantDataset, countGenotypes: Boolean): CountResult = {
    val (nVariants, nCalled) =
      if (countGenotypes) {
        val (nVar, nCalled) = vds.rdd.map { case (v, (va, gs)) =>
          (1L, gs.count(_.isCalled).toLong)
        }.fold((0L, 0L)) { (comb, x) =>
          (comb._1 + x._1, comb._2 + x._2)
        }
        (nVar, Some(nCalled))
      } else
        (vds.countVariants, None)

    CountResult(vds.nSamples, nVariants, nCalled)
  }

  def makeKT(vds: VariantDataset, variantCondition: String, genotypeCondition: String, keyNames: Array[String]): KeyTable = {
    val vSymTab = Map(
      "v" -> (0, TVariant),
      "va" -> (1, vds.vaSignature))
    val vEC = EvalContext(vSymTab)
    val vA = vEC.a

    val (vNames, vTypes, vf) = Parser.parseNamedExprs(variantCondition, vEC)

    val gSymTab = Map(
      "v" -> (0, TVariant),
      "va" -> (1, vds.vaSignature),
      "s" -> (2, TSample),
      "sa" -> (3, vds.saSignature),
      "g" -> (4, TGenotype))
    val gEC = EvalContext(gSymTab)
    val gA = gEC.a

    val (gNames, gTypes, gf) = Parser.parseNamedExprs(genotypeCondition, gEC)

    val sig = TStruct(((vNames, vTypes).zipped ++
      vds.sampleIds.flatMap { s =>
        (gNames, gTypes).zipped.map { case (n, t) =>
          (if (n.isEmpty)
            s
          else
            s + "." + n, t)
        }
      }).toSeq: _*)

    val localSampleIdsBc = vds.sampleIdsBc
    val localSampleAnnotationsBc = vds.sampleAnnotationsBc

    KeyTable(
      vds.rdd.mapPartitions { it =>
        val ab = mutable.ArrayBuilder.make[Any]

        it.map { case (v, (va, gs)) =>
          ab.clear()

          vEC.setAll(v, va)
          vf().foreach { x =>
            ab += x.orNull
          }

          gs.iterator.zipWithIndex.foreach { case (g, i) =>
            val s = localSampleIdsBc.value(i)
            val sa = localSampleAnnotationsBc.value(i)
            gEC.setAll(v, va, s, sa, g)
            gf().foreach { x =>
              ab += x.orNull
            }
          }

          Row.fromSeq(ab.result()): Annotation
        }
      },
      sig,
      keyNames)
  }

  def configureAndCreateSparkContext(appName: String, master: Option[String], local: String = "local[*]",
    parquetCompression: String = "uncompressed", blockSize: Long = 1L): SparkContext = {
    require(blockSize >= 0)

    val conf = new SparkConf().setAppName(appName)

    master match {
      case Some(m) =>
        conf.setMaster(m)
      case None =>
        if (!conf.contains("spark.master"))
          conf.setMaster(local)
    }

    conf.set("spark.ui.showConsoleProgress", "false")

    conf.set(
      "spark.hadoop.io.compression.codecs",
      "org.apache.hadoop.io.compress.DefaultCodec," +
        "is.hail.io.compress.BGzipCodec," +
        "org.apache.hadoop.io.compress.GzipCodec")

    conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")

    val tera = 1024L * 1024L * 1024L * 1024L

    conf.set("spark.sql.parquet.compression.codec", parquetCompression)
    conf.set("spark.sql.files.openCostInBytes", tera.toString)
    conf.set("spark.sql.files.maxPartitionBytes", tera.toString)

    conf.set("spark.hadoop.mapreduce.input.fileinputformat.split.minsize", (blockSize * 1024L * 1024L).toString)

    /* `DataFrame.write` writes one file per partition.  Without this, read will split files larger than the default
     * parquet block size into multiple partitions.  This causes `OrderedRDD` to fail since the per-partition range
     * no longer line up with the RDD partitions.
     *
     * For reasons we don't understand, the DataFrame code uses `SparkHadoopUtil.get.conf` instead of the Hadoop
     * configuration in the SparkContext.  Set both for consistency.
     */
    SparkHadoopUtil.get.conf.setLong("parquet.block.size", tera)
    conf.set("spark.hadoop.parquet.block.size", tera.toString)

    // load additional Spark properties from HAIL_SPARK_PROPERTIES
    val hailSparkProperties = System.getenv("HAIL_SPARK_PROPERTIES")
    if (hailSparkProperties != null) {
      hailSparkProperties
        .split(",")
        .foreach { p =>
          p.split("=") match {
            case Array(k, v) =>
              log.info(s"set Spark property from HAIL_SPARK_PROPERTIES: $k=$v")
              conf.set(k, v)
            case _ =>
              warn(s"invalid key-value property pair in HAIL_SPARK_PROPERTIES: $p")
          }
        }
    }

    log.info(s"Spark properties: ${
      conf.getAll.map { case (k, v) =>
        s"$k=$v"
      }.mkString(", ")
    }")

    val sc = new SparkContext(conf)
    ProgressBarBuilder.build(sc)
    sc
  }

  def configureLogging(logFile: String = "hail.log", quiet: Boolean = false, append: Boolean = false) {
    val logProps = new Properties()
    if (quiet) {
      logProps.put("log4j.rootLogger", "OFF, stderr")
      logProps.put("log4j.appender.stderr", "org.apache.log4j.ConsoleAppender")
      logProps.put("log4j.appender.stderr.Target", "System.err")
      logProps.put("log4j.appender.stderr.threshold", "OFF")
      logProps.put("log4j.appender.stderr.layout", "org.apache.log4j.PatternLayout")
      logProps.put("log4j.appender.stderr.layout.ConversionPattern", "%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n")
    } else {
      logProps.put("log4j.rootLogger", "INFO, logfile")
      logProps.put("log4j.appender.logfile", "org.apache.log4j.FileAppender")
      logProps.put("log4j.appender.logfile.append", append.toString)
      logProps.put("log4j.appender.logfile.file", logFile)
      logProps.put("log4j.appender.logfile.threshold", "INFO")
      logProps.put("log4j.appender.logfile.layout", "org.apache.log4j.PatternLayout")
      logProps.put("log4j.appender.logfile.layout.ConversionPattern", "%d{yyyy-MM-dd HH:mm:ss} %-5p %c{1}:%L - %m%n")
    }

    LogManager.resetConfiguration()
    PropertyConfigurator.configure(logProps)
  }

  def configureHail(branchingFactor: Int = 50, tmpDir: String = "/tmp") {
    require(branchingFactor > 0)

    HailConfiguration.tmpDir = tmpDir
    HailConfiguration.branchingFactor = branchingFactor
  }

  def createSQLContext(sc: SparkContext): SQLContext = {
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    sqlContext
  }
}
