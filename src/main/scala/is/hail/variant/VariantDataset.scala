package is.hail.variant

import java.io.FileNotFoundException

import is.hail.HailContext
import is.hail.annotations.{Annotation, _}
import is.hail.expr.{EvalContext, JSONAnnotationImpex, Parser, SparkAnnotationImpex, TAggregable, TString, TStruct, Type, _}
import is.hail.io._
import is.hail.io.annotators.IntervalListAnnotator
import is.hail.io.plink.ExportBedBimFam
import is.hail.io.vcf.{BufferedLineIterator, ExportVCF}
import is.hail.keytable.KeyTable
import is.hail.methods._
import is.hail.sparkextras.{OrderedPartitioner, OrderedRDD}
import is.hail.utils._
import is.hail.variant.Variant.orderedKey
import org.apache.hadoop
import org.apache.kudu.spark.kudu.{KuduContext, _}
import org.apache.spark.SparkEnv
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{ArrayType, StringType, StructField, StructType}
import org.apache.spark.sql.{Row, SQLContext}
import org.apache.spark.storage.StorageLevel
import org.json4s.jackson.{JsonMethods, Serialization}
import org.json4s.{JArray, JBool, JInt, JObject, JString, JValue, _}

import scala.collection.mutable
import scala.io.Source
import scala.language.implicitConversions
import scala.reflect.ClassTag

object VariantDataset {
  def read(hc: HailContext, dirname: String,
    skipGenotypes: Boolean = false, skipVariants: Boolean = false): VariantDataset = {

    val sqlContext = hc.sqlContext
    val sc = hc.sc
    val hConf = sc.hadoopConfiguration

    val (metadata, parquetGenotypes) = readMetadata(hConf, dirname, skipGenotypes)
    val vaSignature = metadata.vaSignature
    val vaRequiresConversion = SparkAnnotationImpex.requiresConversion(vaSignature)

    val genotypeSignature = metadata.genotypeSignature
    val gRequiresConversion = SparkAnnotationImpex.requiresConversion(genotypeSignature)
    val isGenericGenotype = metadata.isGenericGenotype
    val isDosage = metadata.isDosage

    if (isGenericGenotype)
      fatal("Cannot read datasets with generic genotypes.")

    val parquetFile = dirname + "/rdd.parquet"

    val orderedRDD = if (skipVariants)
      OrderedRDD.empty[Locus, Variant, (Annotation, Iterable[Genotype])](sc)
    else {
      val rdd = if (skipGenotypes)
        sqlContext.readParquetSorted(parquetFile, Some(Array("variant", "annotations")))
          .map(row => (row.getVariant(0),
            (if (vaRequiresConversion) SparkAnnotationImpex.importAnnotation(row.get(1), vaSignature) else row.get(1),
              Iterable.empty[Genotype])))
      else {
        val rdd = sqlContext.readParquetSorted(parquetFile)
        if (parquetGenotypes)
          rdd.map { row =>
            val v = row.getVariant(0)
            (v,
              (if (vaRequiresConversion) SparkAnnotationImpex.importAnnotation(row.get(1), vaSignature) else row.get(1),
                row.getSeq[Row](2).lazyMap { rg =>
                  new RowGenotype(rg): Genotype
                }))
          } else
          rdd.map { row =>
            val v = row.getVariant(0)
            (v,
              (if (vaRequiresConversion) SparkAnnotationImpex.importAnnotation(row.get(1), vaSignature) else row.get(1),
                row.getGenotypeStream(v, 2, isDosage): Iterable[Genotype]))
          }
      }

      val partitioner: OrderedPartitioner[Locus, Variant] =
        try {
          val jv = hConf.readFile(dirname + "/partitioner.json.gz")(JsonMethods.parse(_))
          jv.fromJSON[OrderedPartitioner[Locus, Variant]]
        } catch {
          case _: FileNotFoundException =>
            fatal("missing partitioner.json.gz when loading VDS, create with HailContext.write_partitioning.")
        }

      OrderedRDD(rdd, partitioner)
    }

    new VariantSampleMatrix[Genotype](hc,
      if (skipGenotypes) metadata.copy(sampleIds = IndexedSeq.empty[String],
        sampleAnnotations = IndexedSeq.empty[Annotation])
      else metadata,
      orderedRDD)
  }

  def readMetadata(hConf: hadoop.conf.Configuration, dirname: String,
    requireParquetSuccess: Boolean = true): (VariantMetadata, Boolean) = {
    if (!dirname.endsWith(".vds") && !dirname.endsWith(".vds/"))
      fatal(s"input path ending in `.vds' required, found `$dirname'")

    if (!hConf.exists(dirname))
      fatal(s"no VDS found at `$dirname'")

    val metadataFile = dirname + "/metadata.json.gz"
    val pqtSuccess = dirname + "/rdd.parquet/_SUCCESS"

    if (!hConf.exists(pqtSuccess) && requireParquetSuccess)
      fatal(
        s"""corrupt VDS: no parquet success indicator
           |  Unexpected shutdown occurred during `write'
           |  Recreate VDS.""".stripMargin)

    if (!hConf.exists(metadataFile))
      fatal(
        s"""corrupt or outdated VDS: invalid metadata
           |  No `metadata.json.gz' file found in VDS directory
           |  Recreate VDS with current version of Hail.""".stripMargin)

    val json = try {
      hConf.readFile(metadataFile)(
        in => JsonMethods.parse(in))
    } catch {
      case e: Throwable => fatal(
        s"""
           |corrupt VDS: invalid metadata file.
           |  Recreate VDS with current version of Hail.
           |  caught exception: ${ expandException(e) }
         """.stripMargin)
    }

    val fields = json match {
      case jo: JObject => jo.obj.toMap
      case _ =>
        fatal(
          s"""corrupt VDS: invalid metadata value
             |  Recreate VDS with current version of Hail.""".stripMargin)
    }

    def getAndCastJSON[T <: JValue](fname: String)(implicit tct: ClassTag[T]): T =
      fields.get(fname) match {
        case Some(t: T) => t
        case Some(other) =>
          fatal(
            s"""corrupt VDS: invalid metadata
               |  Expected `${ tct.runtimeClass.getName }' in field `$fname', but got `${ other.getClass.getName }'
               |  Recreate VDS with current version of Hail.""".stripMargin)
        case None =>
          fatal(
            s"""corrupt VDS: invalid metadata
               |  Missing field `$fname'
               |  Recreate VDS with current version of Hail.""".stripMargin)
      }

    val version = getAndCastJSON[JInt]("version").num

    if (version != VariantSampleMatrix.fileVersion)
      fatal(
        s"""Invalid VDS: old version [$version]
           |  Recreate VDS with current version of Hail.
         """.stripMargin)

    val wasSplit = getAndCastJSON[JBool]("split").value
    val isDosage = fields.get("isDosage") match {
      case Some(t: JBool) => t.value
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JBool' in field `isDosage', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => false
    }

    val parquetGenotypes = fields.get("parquetGenotypes") match {
      case Some(t: JBool) => t.value
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JBool' in field `parquetGenotypes', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => false
    }

    val genotypeSignature = fields.get("genotype_schema") match {
      case Some(t: JString) => Parser.parseType(t.s)
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JString' in field `genotype_schema', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => TGenotype
    }

    val isGenericGenotype = fields.get("isGenericGenotype") match {
      case Some(t: JBool) => t.value
      case Some(other) => fatal(
        s"""corrupt VDS: invalid metadata
           |  Expected `JBool' in field `isGenericGenotype', but got `${ other.getClass.getName }'
           |  Recreate VDS with current version of Hail.""".stripMargin)
      case _ => false
    }

    val saSignature = Parser.parseType(getAndCastJSON[JString]("sample_annotation_schema").s)
    val vaSignature = Parser.parseType(getAndCastJSON[JString]("variant_annotation_schema").s)
    val globalSignature = Parser.parseType(getAndCastJSON[JString]("global_annotation_schema").s)

    val sampleInfoSchema = TStruct(("id", TString), ("annotation", saSignature))
    val sampleInfo = getAndCastJSON[JArray]("sample_annotations")
      .arr
      .map {
        case JObject(List(("id", JString(id)), ("annotation", jv: JValue))) =>
          (id, JSONAnnotationImpex.importAnnotation(jv, saSignature, "sample_annotations"))
        case other => fatal(
          s"""corrupt VDS: invalid metadata
             |  Invalid sample annotation metadata
             |  Recreate VDS with current version of Hail.""".stripMargin)
      }
      .toArray

    val globalAnnotation = JSONAnnotationImpex.importAnnotation(getAndCastJSON[JValue]("global_annotation"),
      globalSignature, "global")

    val ids = sampleInfo.map(_._1)
    val annotations = sampleInfo.map(_._2)

    (VariantMetadata(ids, annotations, globalAnnotation,
      saSignature, vaSignature, globalSignature, genotypeSignature, wasSplit, isDosage, isGenericGenotype), parquetGenotypes)
  }

  def readKudu(hc: HailContext, dirname: String, tableName: String,
    master: String): VariantDataset = {

    val (metadata, _) = readMetadata(hc.hadoopConf, dirname, requireParquetSuccess = false)
    val vaSignature = metadata.vaSignature
    val isDosage = metadata.isDosage

    val df = hc.sqlContext.read.options(
      Map("kudu.table" -> tableName, "kudu.master" -> master)).kudu

    val rowType = kuduRowType(vaSignature)
    val schema: StructType = KuduAnnotationImpex.exportType(rowType).asInstanceOf[StructType]

    // Kudu key fields are always first, so we have to reorder the fields we get back
    // to be in the column order for the flattened schema *before* we unflatten
    val indices: Array[Int] = schema.fields.zipWithIndex.map { case (field, rowIdx) =>
      df.schema.fieldIndex(field.name)
    }

    val rdd: RDD[(Variant, (Annotation, Iterable[Genotype]))] = df.rdd.map { row =>
      val importedRow = KuduAnnotationImpex.importAnnotation(
        KuduAnnotationImpex.reorder(row, indices), rowType).asInstanceOf[Row]
      val v = importedRow.getVariant(0)
      (v,
        (importedRow.get(1),
          importedRow.getGenotypeStream(v, 2, metadata.isDosage)))
    }.spanByKey().map(kv => {
      // combine variant rows with different sample groups (no shuffle)
      val variant = kv._1
      val annotations = kv._2.head._1
      // just use first annotation
      val genotypes = kv._2.flatMap(_._2) // combine genotype streams
      (variant, (annotations, genotypes))
    })
    new VariantSampleMatrix[Genotype](hc, metadata, rdd.toOrderedRDD)
  }

  def kuduRowType(vaSignature: Type): Type = TStruct("variant" -> Variant.t,
    "annotations" -> vaSignature,
    "gs" -> GenotypeStream.t,
    "sample_group" -> TString)

  private def makeSchemaForKudu(vaSignature: Type): StructType =
    StructType(Array(
      StructField("variant", Variant.schema, nullable = false),
      StructField("annotations", vaSignature.schema, nullable = false),
      StructField("gs", GenotypeStream.schema, nullable = false),
      StructField("sample_group", StringType, nullable = false)
    ))
}

class VariantDatasetFunctions(private val vds: VariantSampleMatrix[Genotype]) extends AnyVal {

  private def requireSplit(methodName: String) {
    if (!vds.wasSplit)
      fatal(s"method `$methodName' requires a split dataset. Use `split_multi' or `filter_multi' first.")
  }

  /**
    * Aggregate by user-defined key and aggregation expressions.
    *
    * Equivalent of a group-by operation in SQL.
    *
    * @param keyExpr Named expression(s) for which fields are keys
    * @param aggExpr Named aggregation expression(s)
    */
  def aggregateByKey(keyExpr: String, aggExpr: String): KeyTable = {
    val aggregationST = Map(
      "global" -> (0, vds.globalSignature),
      "v" -> (1, TVariant),
      "va" -> (2, vds.vaSignature),
      "s" -> (3, TSample),
      "sa" -> (4, vds.saSignature),
      "g" -> (5, TGenotype))

    val ec = EvalContext(aggregationST.map { case (name, (i, t)) => name -> (i, TAggregable(t, aggregationST)) })

    val keyEC = EvalContext(Map(
      "global" -> (0, vds.globalSignature),
      "v" -> (1, TVariant),
      "va" -> (2, vds.vaSignature),
      "s" -> (3, TSample),
      "sa" -> (4, vds.saSignature),
      "g" -> (5, TGenotype)))

    val (keyNames, keyTypes, keyF) = Parser.parseNamedExprs(keyExpr, keyEC)
    val (aggNames, aggTypes, aggF) = Parser.parseNamedExprs(aggExpr, ec)

    val signature = TStruct((keyNames ++ aggNames, keyTypes ++ aggTypes).zipped.toSeq: _*)

    val (zVals, seqOp, combOp, resultOp) = Aggregators.makeFunctions[Annotation](ec, { case (ec, a) =>
      ec.setAllFromRow(a.asInstanceOf[Row])
    })

    val localGlobalAnnotation = vds.globalAnnotation

    val ktRDD = vds.mapPartitionsWithAll { it =>
      it.map { case (v, va, s, sa, g) =>
        keyEC.setAll(localGlobalAnnotation, v, va, s, sa, g)
        val key = Annotation.fromSeq(keyF())
        (key, Annotation(localGlobalAnnotation, v, va, s, sa, g))
      }
    }.aggregateByKey(zVals)(seqOp, combOp)
      .map { case (k, agg) =>
        resultOp(agg)
        Annotation.fromSeq(k.asInstanceOf[Row].toSeq ++ aggF())
      }

    KeyTable(vds.hc, ktRDD, signature, keyNames)
  }

  def annotateAllelesExpr(expr: String, propagateGQ: Boolean = false): VariantDataset = {
    val isDosage = vds.isDosage

    val (vas2, insertIndex) = vds.vaSignature.insert(TInt, "aIndex")
    val (vas3, insertSplit) = vas2.insert(TBoolean, "wasSplit")
    val localGlobalAnnotation = vds.globalAnnotation

    val aggregationST = Map(
      "global" -> (0, vds.globalSignature),
      "v" -> (1, TVariant),
      "va" -> (2, vas3),
      "g" -> (3, TGenotype),
      "s" -> (4, TSample),
      "sa" -> (5, vds.saSignature))
    val ec = EvalContext(Map(
      "global" -> (0, vds.globalSignature),
      "v" -> (1, TVariant),
      "va" -> (2, vas3),
      "gs" -> (3, TAggregable(TGenotype, aggregationST))))

    val (paths, types, f) = Parser.parseAnnotationExprs(expr, ec, Some(Annotation.VARIANT_HEAD))

    val inserterBuilder = mutable.ArrayBuilder.make[Inserter]
    val finalType = (paths, types).zipped.foldLeft(vds.vaSignature) { case (vas, (ids, signature)) =>
      val (s, i) = vas.insert(TArray(signature), ids)
      inserterBuilder += i
      s
    }
    val inserters = inserterBuilder.result()

    val aggregateOption = Aggregators.buildVariantAggregations(vds, ec)

    vds.mapAnnotations { case (v, va, gs) =>

      val annotations = SplitMulti.split(v, va, gs,
        propagateGQ = propagateGQ,
        keepStar = true,
        isDosage = isDosage,
        insertSplitAnnots = { (va, index, wasSplit) =>
          insertSplit(insertIndex(va, index), wasSplit)
        },
        f = _ => true)
        .map({
          case (v, (va, gs)) =>
            ec.setAll(localGlobalAnnotation, v, va)
            aggregateOption.foreach(f => f(v, va, gs))
            f()
        }).toArray

      inserters.zipWithIndex.foldLeft(va) {
        case (va, (inserter, i)) =>
          inserter(va, annotations.map(_ (i)).toArray[Any]: IndexedSeq[Any])
      }

    }.copy(vaSignature = finalType)
  }

  def annotateSamplesExpr(expr: String): VariantDataset = {
    val ec = Aggregators.sampleEC(vds)

    val (paths, types, f) = Parser.parseAnnotationExprs(expr, ec, Some(Annotation.SAMPLE_HEAD))

    val inserterBuilder = mutable.ArrayBuilder.make[Inserter]
    val finalType = (paths, types).zipped.foldLeft(vds.saSignature) { case (sas, (ids, signature)) =>
      val (s, i) = sas.insert(signature, ids)
      inserterBuilder += i
      s
    }
    val inserters = inserterBuilder.result()

    val sampleAggregationOption = Aggregators.buildSampleAggregations(vds, ec)

    ec.set(0, vds.globalAnnotation)
    val newAnnotations = vds.sampleIdsAndAnnotations.map { case (s, sa) =>
      sampleAggregationOption.foreach(f => f.apply(s))
      ec.set(1, s)
      ec.set(2, sa)
      f().zip(inserters)
        .foldLeft(sa) { case (sa, (v, inserter)) =>
          inserter(sa, v)
        }
    }

    vds.copy(
      sampleAnnotations = newAnnotations,
      saSignature = finalType
    )
  }

  def annotateVariantsExpr(expr: String): VariantDataset = {
    val localGlobalAnnotation = vds.globalAnnotation

    val ec = Aggregators.variantEC(vds)
    val (paths, types, f) = Parser.parseAnnotationExprs(expr, ec, Some(Annotation.VARIANT_HEAD))

    val inserterBuilder = mutable.ArrayBuilder.make[Inserter]
    val finalType = (paths, types).zipped.foldLeft(vds.vaSignature) { case (vas, (ids, signature)) =>
      val (s, i) = vas.insert(signature, ids)
      inserterBuilder += i
      s
    }
    val inserters = inserterBuilder.result()

    val aggregateOption = Aggregators.buildVariantAggregations(vds, ec)

    vds.mapAnnotations { case (v, va, gs) =>
      ec.setAll(localGlobalAnnotation, v, va)

      aggregateOption.foreach(f => f(v, va, gs))
      f().zip(inserters)
        .foldLeft(va) { case (va, (v, inserter)) =>
          inserter(va, v)
        }
    }.copy(vaSignature = finalType)
  }

  def cache(): VariantDataset = persist("MEMORY_ONLY")

  def persist(storageLevel: String = "MEMORY_AND_DISK"): VariantDataset = {
    val level = try {
      StorageLevel.fromString(storageLevel)
    } catch {
      case e: IllegalArgumentException =>
        fatal(s"unknown StorageLevel `$storageLevel'")
    }

    vds.withGenotypeStream().copy(rdd = vds.rdd.persist(level))
  }

  def withGenotypeStream(): VariantDataset = {
    val isDosage = vds.isDosage
    vds.copy(rdd = vds.rdd.mapValuesWithKey[(Annotation, Iterable[Genotype])] { case (v, (va, gs)) =>
      (va, gs.toGenotypeStream(v, isDosage))
    }.asOrderedRDD)
  }

  def coalesce(k: Int, shuffle: Boolean = true): VariantDataset = {
    val start = if (shuffle)
      withGenotypeStream()
    else vds

    start.copy(rdd = start.rdd.coalesce(k, shuffle = shuffle)(null).asOrderedRDD)
  }

  def concordance(other: VariantDataset): (IndexedSeq[IndexedSeq[Long]], VariantDataset, VariantDataset) = {
    requireSplit("concordance")

    if (!other.wasSplit)
      fatal("method `concordance' requires both datasets to be split, but found unsplit right-hand VDS.")

    CalculateConcordance(vds, other)
  }

  def count(countGenotypes: Boolean = false): CountResult = {
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

  def deduplicate(): VariantDataset = {
    DuplicateReport.initialize()

    val acc = DuplicateReport.accumulator
    vds.copy(rdd = vds.rdd.mapPartitions({ it =>
      new SortedDistinctPairIterator(it, (v: Variant) => acc += v)
    }, preservesPartitioning = true).asOrderedRDD)
  }

  def eraseSplit(): VariantDataset = {
    if (vds.wasSplit) {
      val (newSignatures1, f1) = vds.deleteVA("wasSplit")
      val vds1 = vds.copy(vaSignature = newSignatures1)
      val (newSignatures2, f2) = vds1.deleteVA("aIndex")
      vds1.copy(wasSplit = false,
        vaSignature = newSignatures2,
        rdd = vds1.rdd.mapValuesWithKey { case (v, (va, gs)) =>
          (f2(f1(va)), gs.lazyMap(g => g.copy(fakeRef = false)))
        }.asOrderedRDD)
    } else
      vds
  }

  def exportGen(path: String) {
    requireSplit("export gen")

    def writeSampleFile() {
      //FIXME: should output all relevant sample annotations such as phenotype, gender, ...
      vds.hc.hadoopConf.writeTable(path + ".sample",
        "ID_1 ID_2 missing" :: "0 0 0" :: vds.sampleIds.map(s => s"$s $s 0").toList)
    }


    def formatDosage(d: Double): String = d.formatted("%.4f")

    val emptyDosage = Array(0d, 0d, 0d)

    def appendRow(sb: StringBuilder, v: Variant, va: Annotation, gs: Iterable[Genotype], rsidQuery: Querier, varidQuery: Querier) {
      sb.append(v.contig)
      sb += ' '
      sb.append(Option(varidQuery(va)).getOrElse(v.toString))
      sb += ' '
      sb.append(Option(rsidQuery(va)).getOrElse("."))
      sb += ' '
      sb.append(v.start)
      sb += ' '
      sb.append(v.ref)
      sb += ' '
      sb.append(v.alt)

      for (gt <- gs) {
        val dosages = gt.dosage.getOrElse(emptyDosage)
        sb += ' '
        sb.append(formatDosage(dosages(0)))
        sb += ' '
        sb.append(formatDosage(dosages(1)))
        sb += ' '
        sb.append(formatDosage(dosages(2)))
      }
    }

    def writeGenFile() {
      val varidSignature = vds.vaSignature.getOption("varid")
      val varidQuery: Querier = varidSignature match {
        case Some(_) => val (t, q) = vds.queryVA("va.varid")
          t match {
            case TString => q
            case _ => a => None
          }
        case None => a => None
      }

      val rsidSignature = vds.vaSignature.getOption("rsid")
      val rsidQuery: Querier = rsidSignature match {
        case Some(_) => val (t, q) = vds.queryVA("va.rsid")
          t match {
            case TString => q
            case _ => a => None
          }
        case None => a => None
      }

      val isDosage = vds.isDosage

      vds.rdd.mapPartitions { it: Iterator[(Variant, (Annotation, Iterable[Genotype]))] =>
        val sb = new StringBuilder
        it.map { case (v, (va, gs)) =>
          sb.clear()
          appendRow(sb, v, va, gs, rsidQuery, varidQuery)
          sb.result()
        }
      }.writeTable(path + ".gen", vds.hc.tmpDir, None)
    }

    writeSampleFile()
    writeGenFile()
  }

  def exportGenotypes(path: String, expr: String, typeFile: Boolean,
    printRef: Boolean = false, printMissing: Boolean = false) {
    val symTab = Map(
      "v" -> (0, TVariant),
      "va" -> (1, vds.vaSignature),
      "s" -> (2, TSample),
      "sa" -> (3, vds.saSignature),
      "g" -> (4, TGenotype),
      "global" -> (5, vds.globalSignature))

    val ec = EvalContext(symTab)
    ec.set(5, vds.globalAnnotation)
    val (names, ts, f) = Parser.parseExportExprs(expr, ec)

    val hadoopConf = vds.hc.hadoopConf
    if (typeFile) {
      hadoopConf.delete(path + ".types", recursive = false)
      val typeInfo = names
        .getOrElse(ts.indices.map(i => s"_$i").toArray)
        .zip(ts)
      exportTypes(path + ".types", hadoopConf, typeInfo)
    }

    hadoopConf.delete(path, recursive = true)

    val sampleIdsBc = vds.sparkContext.broadcast(vds.sampleIds)
    val sampleAnnotationsBc = vds.sparkContext.broadcast(vds.sampleAnnotations)

    val localPrintRef = printRef
    val localPrintMissing = printMissing

    val filterF: Genotype => Boolean =
      g => (!g.isHomRef || localPrintRef) && (!g.isNotCalled || localPrintMissing)

    val lines = vds.mapPartitionsWithAll { it =>
      val sb = new StringBuilder()
      it
        .filter { case (v, va, s, sa, g) => filterF(g) }
        .map { case (v, va, s, sa, g) =>
          ec.setAll(v, va, s, sa, g)
          sb.clear()

          f().foreachBetween(x => sb.append(x))(sb += '\t')
          sb.result()
        }
    }.writeTable(path, vds.hc.tmpDir, names.map(_.mkString("\t")))
  }

  def exportPlink(path: String, famExpr: String = "id = s.id") {
    requireSplit("export plink")

    val ec = EvalContext(Map(
      "s" -> (0, TSample),
      "sa" -> (1, vds.saSignature),
      "global" -> (2, vds.globalSignature)))

    ec.set(2, vds.globalAnnotation)

    type Formatter = (Option[Any]) => String

    val formatID: Formatter = _.map(_.asInstanceOf[String]).getOrElse("0")
    val formatIsFemale: Formatter = _.map { a =>
      if (a.asInstanceOf[Boolean])
        "2"
      else
        "1"
    }.getOrElse("0")
    val formatIsCase: Formatter = _.map { a =>
      if (a.asInstanceOf[Boolean])
        "2"
      else
        "1"
    }.getOrElse("-9")
    val formatQPheno: Formatter = a => a.map(_.toString).getOrElse("-9")

    val famColumns: Map[String, (Type, Int, Formatter)] = Map(
      "famID" -> (TString, 0, formatID),
      "id" -> (TString, 1, formatID),
      "patID" -> (TString, 2, formatID),
      "matID" -> (TString, 3, formatID),
      "isFemale" -> (TBoolean, 4, formatIsFemale),
      "qPheno" -> (TDouble, 5, formatQPheno),
      "isCase" -> (TBoolean, 5, formatIsCase))

    val (names, types, f) = Parser.parseNamedExprs(famExpr, ec)

    val famFns: Array[(Array[Option[Any]]) => String] = Array(
      _ => "0", _ => "0", _ => "0", _ => "0", _ => "-9", _ => "-9")

    (names.zipWithIndex, types).zipped.foreach { case ((name, i), t) =>
      famColumns.get(name) match {
        case Some((colt, j, formatter)) =>
          if (colt != t)
            fatal("invalid type for .fam file column $h: expected $colt, got $t")
          famFns(j) = (a: Array[Option[Any]]) => formatter(a(i))

        case None =>
          fatal(s"no .fam file column $name")
      }
    }

    val spaceRegex = """\s+""".r
    val badSampleIds = vds.sampleIds.filter(id => spaceRegex.findFirstIn(id).isDefined)
    if (badSampleIds.nonEmpty) {
      fatal(
        s"""Found ${ badSampleIds.length } sample IDs with whitespace
           |  Please run `renamesamples' to fix this problem before exporting to plink format
           |  Bad sample IDs: @1 """.stripMargin, badSampleIds)
    }

    val bedHeader = Array[Byte](108, 27, 1)

    val plinkRDD = vds.rdd
      .mapValuesWithKey { case (v, (va, gs)) => ExportBedBimFam.makeBedRow(gs) }
      .persist(StorageLevel.MEMORY_AND_DISK)

    plinkRDD.map { case (v, bed) => bed }
      .saveFromByteArrays(path + ".bed", vds.hc.tmpDir, header = Some(bedHeader))

    plinkRDD.map { case (v, bed) => ExportBedBimFam.makeBimRow(v) }
      .writeTable(path + ".bim", vds.hc.tmpDir)

    plinkRDD.unpersist()

    val famRows = vds
      .sampleIdsAndAnnotations
      .map { case (s, sa) =>
        ec.setAll(s, sa)
        val a = f().map(Option(_))
        famFns.map(_ (a)).mkString("\t")
      }

    vds.hc.hadoopConf.writeTextFile(path + ".fam")(out =>
      famRows.foreach(line => {
        out.write(line)
        out.write("\n")
      }))
  }

  def exportSamples(path: String, expr: String, typeFile: Boolean = false) {
    val localGlobalAnnotation = vds.globalAnnotation

    val ec = Aggregators.sampleEC(vds)

    val (names, types, f) = Parser.parseExportExprs(expr, ec)
    val hadoopConf = vds.hc.hadoopConf
    if (typeFile) {
      hadoopConf.delete(path + ".types", recursive = false)
      val typeInfo = names
        .getOrElse(types.indices.map(i => s"_$i").toArray)
        .zip(types)
      exportTypes(path + ".types", hadoopConf, typeInfo)
    }

    val sampleAggregationOption = Aggregators.buildSampleAggregations(vds, ec)

    hadoopConf.delete(path, recursive = true)

    val sb = new StringBuilder()
    val lines = for ((s, sa) <- vds.sampleIdsAndAnnotations) yield {
      sampleAggregationOption.foreach(f => f.apply(s))
      sb.clear()
      ec.setAll(localGlobalAnnotation, s, sa)
      f().foreachBetween(x => sb.append(x))(sb += '\t')
      sb.result()
    }

    hadoopConf.writeTable(path, lines, names.map(_.mkString("\t")))
  }

  /**
    *
    * @param path output path
    * @param append append file to header
    * @param exportPP export Hail PLs as a PP format field
    * @param parallel export VCF in parallel using the path argument as a directory
    */
  def exportVCF(path: String, append: Option[String] = None, exportPP: Boolean = false, parallel: Boolean = false) {
    ExportVCF(vds, path, append, exportPP, parallel)
  }

  def exportVariants(path: String, expr: String, typeFile: Boolean = false) {
    val vas = vds.vaSignature
    val hConf = vds.hc.hadoopConf

    val localGlobalAnnotations = vds.globalAnnotation
    val ec = Aggregators.variantEC(vds)

    val (names, types, f) = Parser.parseExportExprs(expr, ec)

    val hadoopConf = vds.hc.hadoopConf
    if (typeFile) {
      hadoopConf.delete(path + ".types", recursive = false)
      val typeInfo = names
        .getOrElse(types.indices.map(i => s"_$i").toArray)
        .zip(types)
      exportTypes(path + ".types", hadoopConf, typeInfo)
    }

    val variantAggregations = Aggregators.buildVariantAggregations(vds, ec)

    hadoopConf.delete(path, recursive = true)

    vds.rdd
      .mapPartitions { it =>
        val sb = new StringBuilder()
        it.map { case (v, (va, gs)) =>
          variantAggregations.foreach { f => f(v, va, gs) }
          ec.setAll(localGlobalAnnotations, v, va)
          sb.clear()
          f().foreachBetween(x => sb.append(x))(sb += '\t')
          sb.result()
        }
      }.writeTable(path, vds.hc.tmpDir, names.map(_.mkString("\t")))
  }

  /**
    *
    * @param address Cassandra contact point to connect to
    * @param keySpace Cassandra keyspace
    * @param table Cassandra table
    * @param genotypeExpr comma-separated list of fields/computations to be exported
    * @param variantExpr comma-separated list of fields/computations to be exported
    * @param drop drop and re-create Cassandra table before exporting
    * @param exportRef export HomRef calls
    * @param exportMissing export missing genotypes
    * @param blockSize size of exported batch
    */
  def exportVariantsCassandra(address: String, genotypeExpr: String, keySpace: String,
    table: String, variantExpr: String, drop: Boolean = false, exportRef: Boolean = false,
    exportMissing: Boolean = false, blockSize: Int = 100) {
    requireSplit("export variants cassandra")

    CassandraConnector.exportVariants(vds, address, keySpace, table, genotypeExpr,
      variantExpr, drop, exportRef, exportMissing, blockSize)
  }

  /**
    *
    * @param variantExpr comma-separated list of fields/computations to be exported
    * @param genotypeExpr comma-separated list of fields/computations to be exported
    * @param collection SolrCloud collection
    * @param url Solr instance (URL) to connect to
    * @param zkHost Zookeeper host string to connect to
    * @param exportMissing export missing genotypes
    * @param exportRef export HomRef calls
    * @param drop delete and re-create solr collection before exporting
    * @param numShards number of shards to split the collection into
    * @param blockSize Variants per SolrClient.add
    */
  def exportVariantsSolr(variantExpr: String,
    genotypeExpr: String,
    collection: String = null,
    url: String = null,
    zkHost: String = null,
    exportMissing: Boolean = false,
    exportRef: Boolean = false,
    drop: Boolean = false,
    numShards: Int = 1,
    blockSize: Int = 100) {
    requireSplit("export variants solr")

    SolrConnector.exportVariants(vds, variantExpr, genotypeExpr, collection, url, zkHost, exportMissing,
      exportRef, drop, numShards, blockSize)
  }

  /**
    *
    * @param filterExpr Filter expression involving v (variant), va (variant annotations), and aIndex (allele index)
    * @param annotationExpr Annotation modifying expression involving v (new variant), va (old variant annotations),
    *                       and aIndices (maps from new to old indices)
    * @param filterAlteredGenotypes any call that contains a filtered allele is set to missing instead
    * @param keep Keep variants matching condition
    * @param subset subsets the PL and AD. Genotype and GQ are set based on the resulting PLs.  Downcodes by default.
    * @param maxShift Maximum possible position change during minimum representation calculation
    */
  def filterAlleles(filterExpr: String, annotationExpr: String = "va = va", filterAlteredGenotypes: Boolean = false,
    keep: Boolean = true, subset: Boolean = true, maxShift: Int = 100): VariantDataset = {
    FilterAlleles(vds, filterExpr, annotationExpr, filterAlteredGenotypes, keep, subset, maxShift)
  }

  /**
    *
    * @param filterExpr filter expression involving v (Variant), va (variant annotations), s (sample),
    *                   sa (sample annotations), and g (genotype), which returns a boolean value
    * @param keep keep genotypes where filterExpr evaluates to true
    */
  def filterGenotypes(filterExpr: String, keep: Boolean = true): VariantDataset = {
    val vas = vds.vaSignature
    val sas = vds.saSignature

    val symTab = Map(
      "v" -> (0, TVariant),
      "va" -> (1, vas),
      "s" -> (2, TSample),
      "sa" -> (3, sas),
      "g" -> (4, TGenotype),
      "global" -> (5, vds.globalSignature))


    val ec = EvalContext(symTab)
    ec.set(5, vds.globalAnnotation)
    val f: () => java.lang.Boolean = Parser.parseTypedExpr[java.lang.Boolean](filterExpr, ec)

    val sampleIdsBc = vds.sampleIdsBc
    val sampleAnnotationsBc = vds.sampleAnnotationsBc

    (vds.sampleIds, vds.sampleAnnotations).zipped.map((_, _))

    val noCall = Genotype()
    val localKeep = keep
    vds.mapValuesWithAll(
      (v: Variant, va: Annotation, s: String, sa: Annotation, g: Genotype) => {
        ec.setAll(v, va, s, sa, g)

        if (Filter.boxedKeepThis(f(), localKeep))
          g
        else
          noCall
      })
  }

  /**
    * Remove multiallelic variants from this dataset.
    *
    * Useful for running methods that require biallelic variants without calling the more expensive split_multi step.
    */
  def filterMulti(): VariantDataset = {
    if (vds.wasSplit) {
      warn("called redundant `filtermulti' on an already split or multiallelic-filtered VDS")
      vds
    } else {
      vds.filterVariants {
        case (v, va, gs) => v.isBiallelic
      }.copy(wasSplit = true)
    }
  }

  /**
    * Filter samples using the Hail expression language.
    *
    * @param filterExpr Filter expression involving `s' (sample) and `sa' (sample annotations)
    * @param keep keep where filterExpr evaluates to true
    */
  def filterSamplesExpr(filterExpr: String, keep: Boolean = true): VariantDataset = {
    val localGlobalAnnotation = vds.globalAnnotation

    val sas = vds.saSignature

    val ec = Aggregators.sampleEC(vds)

    val f: () => java.lang.Boolean = Parser.parseTypedExpr[java.lang.Boolean](filterExpr, ec)

    val sampleAggregationOption = Aggregators.buildSampleAggregations(vds, ec)

    val localKeep = keep
    val sampleIds = vds.sampleIds
    val p = (s: String, sa: Annotation) => {
      sampleAggregationOption.foreach(f => f.apply(s))
      ec.setAll(localGlobalAnnotation, s, sa)
      Filter.boxedKeepThis(f(), localKeep)
    }

    vds.filterSamples(p)
  }

  /**
    * Filter variants using the Hail expression language.
    * @param filterExpr filter expression
    * @param keep keep variants where filterExpr evaluates to true
    * @return
    */
  def filterVariantsExpr(filterExpr: String, keep: Boolean = true): VariantDataset = {
    val localGlobalAnnotation = vds.globalAnnotation
    val ec = Aggregators.variantEC(vds)

    val f: () => java.lang.Boolean = Parser.parseTypedExpr[java.lang.Boolean](filterExpr, ec)

    val aggregatorOption = Aggregators.buildVariantAggregations(vds, ec)

    val localKeep = keep
    val p = (v: Variant, va: Annotation, gs: Iterable[Genotype]) => {
      aggregatorOption.foreach(f => f(v, va, gs))

      ec.setAll(localGlobalAnnotation, v, va)
      Filter.boxedKeepThis(f(), localKeep)
    }

    vds.filterVariants(p)
  }

  def gqByDP(path: String) {
    val nBins = GQByDPBins.nBins
    val binStep = GQByDPBins.binStep
    val firstBinLow = GQByDPBins.firstBinLow
    val gqbydp = GQByDPBins(vds)

    vds.hadoopConf.writeTextFile(path) { s =>
      s.write("sample")
      for (b <- 0 until nBins)
        s.write("\t" + GQByDPBins.binLow(b) + "-" + GQByDPBins.binHigh(b))

      s.write("\n")

      for (sample <- vds.sampleIds) {
        s.write(sample)
        for (b <- 0 until GQByDPBins.nBins) {
          gqbydp.get((sample, b)) match {
            case Some(percentGQ) => s.write("\t" + percentGQ)
            case None => s.write("\tNA")
          }
        }
        s.write("\n")
      }
    }
  }

  /**
    *
    * @param path output path
    * @param format output format: one of rel, gcta-grm, gcta-grm-bin
    * @param idFile write ID file to this path
    * @param nFile N file path, used with gcta-grm-bin only
    */
  def grm(path: String, format: String, idFile: Option[String] = None, nFile: Option[String] = None) {
    requireSplit("GRM")
    GRM(vds, path, format, idFile, nFile)
  }

  def hardCalls(): VariantDataset = {
    vds.mapValues { g => Genotype(g.gt, g.fakeRef) }
  }

  /**
    *
    * @param computeMafExpr An expression for the minor allele frequency of the current variant, `v', given
    *                       the variant annotations `va'. If unspecified, MAF will be estimated from the dataset
    * @param bounded Allows the estimations for Z0, Z1, Z2, and PI_HAT to take on biologically-nonsense values
    *                (e.g. outside of [0,1]).
    * @param minimum Sample pairs with a PI_HAT below this value will not be included in the output. Must be in [0,1]
    * @param maximum Sample pairs with a PI_HAT above this value will not be included in the output. Must be in [0,1]
    */
  def ibd(computeMafExpr: Option[String] = None, bounded: Boolean = true,
    minimum: Option[Double] = None, maximum: Option[Double] = None): KeyTable = {
    requireSplit("IBD")

    IBD.toKeyTable(vds.hc, IBD.validateAndCall(vds, computeMafExpr, bounded, minimum, maximum))
  }

  /**
    *
    * @param mafThreshold Minimum minor allele frequency threshold
    * @param includePAR Include pseudoautosomal regions
    * @param fFemaleThreshold Samples are called females if F < femaleThreshold
    * @param fMaleThreshold Samples are called males if F > maleThreshold
    * @param popFreqExpr Use an annotation expression for estimate of MAF rather than computing from the data
    */
  def imputeSex(mafThreshold: Double = 0.0, includePAR: Boolean = false, fFemaleThreshold: Double = 0.2,
    fMaleThreshold: Double = 0.8, popFreqExpr: Option[String] = None): VariantDataset = {
    requireSplit("impute sex")

    val result = ImputeSexPlink(vds,
      mafThreshold,
      includePAR,
      fMaleThreshold,
      fFemaleThreshold,
      popFreqExpr)

    val signature = ImputeSexPlink.schema

    vds.annotateSamples(result, signature, "sa.imputesex")
  }

  /**
    *
    * @param right right-hand dataset with which to join
    */
  def join(right: VariantDataset): VariantDataset = {
    if (vds.wasSplit != right.wasSplit) {
      warn(
        s"""cannot join split and unsplit datasets
           |  left was split: ${ vds.wasSplit }
           |  light was split: ${ right.wasSplit }""".stripMargin)
    }

    if (vds.saSignature != right.saSignature) {
      fatal(
        s"""cannot join datasets with different sample schemata
           |  left sample schema: @1
           |  right sample schema: @2""".stripMargin,
        vds.saSignature.toPrettyString(compact = true, printAttrs = true),
        right.saSignature.toPrettyString(compact = true, printAttrs = true))
    }

    val newSampleIds = vds.sampleIds ++ right.sampleIds
    val duplicates = newSampleIds.duplicates()
    if (duplicates.nonEmpty)
      fatal("duplicate sample IDs: @1", duplicates)

    val joined = vds.rdd.orderedInnerJoinDistinct(right.rdd)
      .mapValues { case ((lva, lgs), (rva, rgs)) =>
        (lva, lgs ++ rgs)
      }.asOrderedRDD

    vds.copy(
      sampleIds = newSampleIds,
      sampleAnnotations = vds.sampleAnnotations ++ right.sampleAnnotations,
      rdd = joined)
  }

  def linreg(ySA: String, covSA: Array[String], root: String, minAC: Int, minAF: Double): VariantDataset = {
    requireSplit("linear regression")
    LinearRegression(vds, ySA, covSA, root, minAC, minAF)
  }

  def lmmreg(kinshipVDS: VariantDataset, ySA: String,
    covSA: Array[String],
    useML: Boolean,
    rootGA: String,
    rootVA: String,
    runAssoc: Boolean,
    optDelta: Option[Double],
    sparsityThreshold: Double,
    forceBlock: Boolean,
    forceGrammian: Boolean): VariantDataset = {
    requireSplit("linear mixed regression")
    LinearMixedRegression(vds, kinshipVDS, ySA, covSA, useML, rootGA, rootVA,
      runAssoc, optDelta, sparsityThreshold, forceBlock, forceGrammian)
  }

  def logreg(test: String, ySA: String, covSA: Array[String], root: String): VariantDataset = {
    requireSplit("logistic regression")
    LogisticRegression(vds, test, ySA, covSA, root)
  }

  def makeKT(variantCondition: String, genotypeCondition: String, keyNames: Array[String]): KeyTable = {
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

    val localNSamples = vds.nSamples
    val localSampleIdsBc = vds.sampleIdsBc
    val localSampleAnnotationsBc = vds.sampleAnnotationsBc

    KeyTable(vds.hc,
      vds.rdd.mapPartitions { it =>
        val n = vNames.length + gNames.length * localNSamples

        it.map { case (v, (va, gs)) =>
          val a = new Array[Any](n)

          var j = 0
          vEC.setAll(v, va)
          vf().foreach { x =>
            a(j) = x
            j += 1
          }

          gs.iterator.zipWithIndex.foreach { case (g, i) =>
            val s = localSampleIdsBc.value(i)
            val sa = localSampleAnnotationsBc.value(i)
            gEC.setAll(v, va, s, sa, g)
            gf().foreach { x =>
              a(j) = x
              j += 1
            }
          }

          assert(j == n)
          Row.fromSeq(a): Annotation
        }
      },
      sig,
      keyNames)
  }

  def makeSchemaForKudu(): StructType =
    makeSchema(parquetGenotypes = false).add(StructField("sample_group", StringType, nullable = false))

  /**
    *
    * @param pathBase output root filename
    * @param famFile path to pedigree .fam file
    */
  def mendelErrors(pathBase: String, famFile: String) {
    requireSplit("mendel errors")

    val ped = Pedigree.read(famFile, vds.hc.hadoopConf, vds.sampleIds)
    val men = MendelErrors(vds, ped.completeTrios)

    men.writeMendel(pathBase + ".mendel", vds.hc.tmpDir)
    men.writeMendelL(pathBase + ".lmendel", vds.hc.tmpDir)
    men.writeMendelF(pathBase + ".fmendel")
    men.writeMendelI(pathBase + ".imendel")
  }

  /**
    *
    * @param scoresRoot Sample annotation path for scores (period-delimited path starting in 'sa')
    * @param k Number of principal components
    * @param loadingsRoot Variant annotation path for site loadings (period-delimited path starting in 'va')
    * @param eigenRoot Global annotation path for eigenvalues (period-delimited path starting in 'global'
    * @param asArrays Store score and loading results as arrays, rather than structs
    */
  def pca(scoresRoot: String, k: Int = 10, loadingsRoot: Option[String] = None, eigenRoot: Option[String] = None,
    asArrays: Boolean = false): VariantDataset = {
    requireSplit("PCA")

    if (k < 1)
      fatal(
        s"""requested invalid number of components: $k
           |  Expect componenents >= 1""".stripMargin)

    info(s"Running PCA with $k components...")

    val pcSchema = SamplePCA.pcSchema(asArrays, k)

    val (scores, loadings, eigenvalues) =
      SamplePCA(vds, k, loadingsRoot.isDefined, eigenRoot.isDefined, asArrays)

    var ret = vds.annotateSamples(scores, pcSchema, scoresRoot)

    loadings.foreach { rdd =>
      ret = ret.annotateVariants(rdd.orderedRepartitionBy(vds.rdd.orderedPartitioner), pcSchema, loadingsRoot.get)
    }

    eigenvalues.foreach { eig =>
      ret = ret.annotateGlobal(eig, pcSchema, eigenRoot.get)
    }
    ret
  }


  def queryGenotypes(expr: String): (Annotation, Type) = {
    val qv = queryGenotypes(Array(expr))
    assert(qv.length == 1)
    qv.head
  }

  def queryGenotypes(exprs: Array[String]): Array[(Annotation, Type)] = {
    val aggregationST = Map(
      "global" -> (0, vds.globalSignature),
      "g" -> (1, TGenotype),
      "v" -> (2, TVariant),
      "va" -> (3, vds.vaSignature),
      "s" -> (4, TSample),
      "sa" -> (5, vds.saSignature))
    val ec = EvalContext(Map(
      "global" -> (0, vds.globalSignature),
      "gs" -> (1, TAggregable(TGenotype, aggregationST))))

    val ts = exprs.map(e => Parser.parseExpr(e, ec))
    println("HERE 1")

    val localGlobalAnnotation = vds.globalAnnotation
    val (zVal, seqOp, combOp, resOp) = Aggregators.makeFunctions[Genotype](ec, { case (ec, g) =>
      ec.set(1, g)
    })

    val sampleIdsBc = vds.sampleIdsBc
    val sampleAnnotationsBc = vds.sampleAnnotationsBc
    val global = vds.globalAnnotation

    val a = ec.a
    val result = vds.rdd.mapPartitions { it =>
      val zv = zVal.map(_.copy())
      ec.set(0, global)
      try {
        println(s"it has next is ${it.hasNext}")
      } catch {
        case e: Throwable => println(s"failed: $e")
          println(e.getStackTrace.mkString("\n"))
      }
      println("GOT HERE")
      it.foreach { case (v, (va, gs)) =>
        var i = 0
        ec.set(2, v)
        ec.set(3, va)
        gs.foreach { g =>
          ec.set(4, sampleIdsBc.value(i))
          ec.set(5, sampleAnnotationsBc.value(i))
          seqOp(zv, g)
          i += 1
        }
      }
      println(s"HERE 5, about to return ${ zv.toSeq }")
      Iterator(zv)
      //    }.fold(zVal.map(_.copy()))(combOp)
    }.collect()

    println(result.toSeq.map(_.toSeq))


//    }.fold(zVal.map(_.copy())) { case (aggA, aggB) => println(aggA.toSeq); println(aggB.toSeq); combOp(aggA, aggB)}
//    resOp(result)
//
//    ec.set(0, localGlobalAnnotation)
//    ts.map { case (t, f) => (f(), t) }
    ???
  }

  def sampleQC(): VariantDataset = SampleQC(vds)

  /**
    *
    * @param propagateGQ Propagate GQ instead of computing from PL
    * @param keepStar Do not filter * alleles
    * @param maxShift Maximum possible position change during minimum representation calculation
    */
  def splitMulti(propagateGQ: Boolean = false, keepStar: Boolean = false,
    maxShift: Int = 100): VariantDataset = {
    SplitMulti(vds, propagateGQ, keepStar, maxShift)
  }

  /**
    *
    * @param famFile path to .fam file
    * @param tdtRoot Annotation root, starting in 'va'
    */
  def tdt(famFile: String, tdtRoot: String = "va.tdt"): VariantDataset = {
    requireSplit("TDT")

    val ped = Pedigree.read(famFile, vds.hc.hadoopConf, vds.sampleIds)
    TDT(vds, ped.completeTrios,
      Parser.parseAnnotationRoot(tdtRoot, Annotation.VARIANT_HEAD))
  }

  def variantQC(): VariantDataset = {
    requireSplit("variant QC")
    VariantQC(vds)
  }

  /**
    *
    * @param config VEP configuration file
    * @param root Variant annotation path to store VEP output
    * @param csq Annotates with the VCF CSQ field as a string, rather than the full nested struct schema
    * @param force Force VEP annotation from scratch
    * @param blockSize Variants per VEP invocation
    */
  def vep(config: String, root: String = "va.vep", csq: Boolean = false, force: Boolean = false,
    blockSize: Int = 1000): VariantDataset = {
    VEP.annotate(vds, config, root, csq, force, blockSize)
  }

  def write(dirname: String, overwrite: Boolean = false, parquetGenotypes: Boolean = false) {
    require(dirname.endsWith(".vds"), "variant dataset write paths must end in '.vds'")

    if (overwrite)
      vds.hadoopConf.delete(dirname, recursive = true)
    else if (vds.hadoopConf.exists(dirname))
      fatal(s"file already exists at `$dirname'")

    vds.writeMetadata(dirname, parquetGenotypes = parquetGenotypes)

    val vaSignature = vds.vaSignature
    val vaRequiresConversion = SparkAnnotationImpex.requiresConversion(vaSignature)

    val genotypeSignature = vds.genotypeSignature
    require(genotypeSignature == TGenotype, s"Expecting a genotype signature of TGenotype, but found `${genotypeSignature.toPrettyString()}'")

    vds.hadoopConf.writeTextFile(dirname + "/partitioner.json.gz") { out =>
      Serialization.write(vds.rdd.orderedPartitioner.toJSON, out)
    }

    val isDosage = vds.isDosage
    val rowRDD = vds.rdd.map { case (v, (va, gs)) =>
      Row.fromSeq(Array(v.toRow,
        if (vaRequiresConversion) SparkAnnotationImpex.exportAnnotation(va, vaSignature) else va,
        if (parquetGenotypes)
          gs.lazyMap(_.toRow).toArray[Row]: IndexedSeq[Row]
        else
          gs.toGenotypeStream(v, isDosage).toRow))
    }
    vds.hc.sqlContext.createDataFrame(rowRDD, makeSchema(parquetGenotypes = parquetGenotypes))
      .write.parquet(dirname + "/rdd.parquet")
  }

  def makeSchema(parquetGenotypes: Boolean): StructType = {
    require(!(parquetGenotypes && vds.isGenericGenotype))
    StructType(Array(
      StructField("variant", Variant.schema, nullable = false),
      StructField("annotations", vds.vaSignature.schema),
      StructField("gs",
        if (parquetGenotypes)
          ArrayType(Genotype.schema, containsNull = false)
        else
          GenotypeStream.schema,
        nullable = false)
    ))
  }

  def writeKudu(dirname: String, tableName: String,
    master: String, vcfSeqDict: String, rowsPerPartition: Int,
    sampleGroup: String, drop: Boolean = false) {
    requireSplit("write Kudu")

    vds.writeMetadata(dirname, parquetGenotypes = false)

    val vaSignature = vds.vaSignature
    val isDosage = vds.isDosage

    val rowType = VariantDataset.kuduRowType(vaSignature)
    val rowRDD = vds.rdd
      .map { case (v, (va, gs)) =>
        KuduAnnotationImpex.exportAnnotation(Annotation(
          v.toRow,
          va,
          gs.toGenotypeStream(v, isDosage).toRow,
          sampleGroup), rowType).asInstanceOf[Row]
      }

    val schema: StructType = KuduAnnotationImpex.exportType(rowType).asInstanceOf[StructType]
    println(s"schema = $schema")
    val df = vds.hc.sqlContext.createDataFrame(rowRDD, schema)

    val kuduContext = new KuduContext(master)
    if (drop) {
      KuduUtils.dropTable(master, tableName)
      Thread.sleep(10 * 1000) // wait to avoid overwhelming Kudu service queue
    }
    if (!KuduUtils.tableExists(master, tableName)) {
      val hConf = vds.hc.sqlContext.sparkContext.hadoopConfiguration
      val headerLines = hConf.readFile(vcfSeqDict) { s =>
        Source.fromInputStream(s)
          .getLines()
          .takeWhile { line => line(0) == '#' }
          .toArray
      }
      val codec = new htsjdk.variant.vcf.VCFCodec()
      val seqDict = codec.readHeader(new BufferedLineIterator(headerLines.iterator.buffered))
        .getHeaderValue
        .asInstanceOf[htsjdk.variant.vcf.VCFHeader]
        .getSequenceDictionary

      val keys = Seq("variant__contig", "variant__start", "variant__ref",
        "variant__altAlleles_0__alt", "sample_group")
      kuduContext.createTable(tableName, schema, keys,
        KuduUtils.createTableOptions(schema, keys, seqDict, rowsPerPartition))
    }
    df.write
      .options(Map("kudu.master" -> master, "kudu.table" -> tableName))
      .mode("append")
      // FIXME inlined since .kudu wouldn't work for some reason
      .format("org.apache.kudu.spark.kudu").save

    info("Written to Kudu")
  }

  def toGDS: GenericDataset = vds.mapValues(g => g: Any).copy(isGenericGenotype = true)
}
