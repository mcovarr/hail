package org.apache.spark.sql.execution.datasources.parquet

import java.net.URI
import java.util.logging.{Logger => JLogger}
import java.util.{List => JList}

import org.apache.hadoop.fs._
import org.apache.hadoop.io.Writable
import org.apache.hadoop.mapreduce._
import parquet.hadoop._
import parquet.{Log => ApacheParquetLog}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.{RDD, SqlNewHadoopPartition, SqlNewHadoopRDD}
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.execution.datasources.PartitionSpec
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.{SerializableConfiguration, Utils}
import org.apache.spark.{Partition => SparkPartition}

import scala.collection.JavaConversions._


/**
  * Copied and slightly modified from:
  *   org.apache.spark.sql.execution.datasources.parquet.ParquetRelation
  *   version 1.5.0
  *
  * Changed to use PartitionedParquetInputFormat instead of ParquetInputFormat.
  */
class PartitionedParquetRelation(paths: Array[String],
  maybeDataSchema: Option[StructType],
  maybePartitionSpec: Option[PartitionSpec],
  userDefinedPartitionColumns: Option[StructType],
  parameters: Map[String, String])(
  sqlContext: SQLContext) extends ParquetRelation(paths, maybeDataSchema, maybePartitionSpec,
  userDefinedPartitionColumns, parameters)(sqlContext) {
  override def buildScan(
    requiredColumns: Array[String],
    filters: Array[Filter],
    inputFiles: Array[FileStatus],
    broadcastedConf: Broadcast[SerializableConfiguration]): RDD[Row] = {
    val useMetadataCache = sqlContext.getConf(SQLConf.PARQUET_CACHE_METADATA)
    val parquetFilterPushDown = sqlContext.conf.parquetFilterPushDown
    val assumeBinaryIsString = sqlContext.conf.isParquetBinaryAsString
    val assumeInt96IsTimestamp = sqlContext.conf.isParquetINT96AsTimestamp

    // Parquet row group size. We will use this value as the value for
    // mapreduce.input.fileinputformat.split.minsize and mapred.min.split.size if the value
    // of these flags are smaller than the parquet row group size.
    val parquetBlockSize = ParquetOutputFormat.getLongBlockSize(broadcastedConf.value.value)

    // Create the function to set variable Parquet confs at both driver and executor side.
    val initLocalJobFuncOpt =
    ParquetRelation.initializeLocalJobFunc(
      requiredColumns,
      filters,
      dataSchema,
      parquetBlockSize,
      useMetadataCache,
      parquetFilterPushDown,
      assumeBinaryIsString,
      assumeInt96IsTimestamp) _

    val setInputPaths =
      ParquetRelation.initializeDriverSideJobFunc(inputFiles, parquetBlockSize) _

    Utils.withDummyCallSite(sqlContext.sparkContext) {
      new SqlNewHadoopRDD(
        sqlContext = sqlContext,
        broadcastedConf = broadcastedConf,
        initDriverSideJobFuncOpt = Some(setInputPaths),
        initLocalJobFuncOpt = Some(initLocalJobFuncOpt),
        inputFormatClass = classOf[PartitionedParquetInputFormat[InternalRow]],
        valueClass = classOf[InternalRow]) {

        val cacheMetadata = useMetadataCache

        @transient val cachedStatuses = inputFiles.map { f =>
          // In order to encode the authority of a Path containing special characters such as '/'
          // (which does happen in some S3N credentials), we need to use the string returned by the
          // URI of the path to create a new Path.
          val pathWithEscapedAuthority = escapePathUserInfo(f.getPath)
          new FileStatus(
            f.getLen, f.isDirectory, f.getReplication, f.getBlockSize, f.getModificationTime,
            f.getAccessTime, f.getPermission, f.getOwner, f.getGroup, pathWithEscapedAuthority)
        }.toSeq

        private def escapePathUserInfo(path: Path): Path = {
          val uri = path.toUri
          new Path(new URI(
            uri.getScheme, uri.getRawUserInfo, uri.getHost, uri.getPort, uri.getPath,
            uri.getQuery, uri.getFragment))
        }

        // Overridden so we can inject our own cached files statuses.
        override def getPartitions: Array[SparkPartition] = {
          val inputFormat = new PartitionedParquetInputFormat[InternalRow] {
            override def listStatus(jobContext: JobContext): JList[FileStatus] = {
              if (cacheMetadata) cachedStatuses else super.listStatus(jobContext)
            }
          }

          val jobContext: JobContext = newJobContext(getJob().getConfiguration, jobId)
          val rawSplits = inputFormat.getSplits(jobContext)

          Array.tabulate[SparkPartition](rawSplits.size) { i =>
            new SqlNewHadoopPartition(id, i, rawSplits(i).asInstanceOf[InputSplit with Writable])
          }
        }
      }.asInstanceOf[RDD[Row]] // type erasure hack to pass RDD[InternalRow] as RDD[Row]
    }
  }
}