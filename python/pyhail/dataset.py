from pyhail.java import scala_package_object, jarray
from pyhail.keytable import KeyTable
from pyhail.utils import TextTableConfig

from py4j.protocol import Py4JJavaError


class VariantDataset(object):
    def __init__(self, hc, jvds):
        self.hc = hc
        self.jvds = jvds

    def _raise_py4j_exception(self, e):
        self.hc._raise_py4j_exception(e)

    def sample_ids(self):
        """Return sampleIDs.

        :return: List of sample IDs.

        :rtype: list of str

        """
        try:
            return list(self.jvds.sampleIdsAsArray())
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def num_partitions(self):
        """Number of RDD partitions.

        :rtype: int

        """
        try:
            return self.jvds.nPartitions()
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def num_samples(self):
        """Number of samples.

        :rtype: int

        """
        try:
            return self.jvds.nSamples()
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def num_variants(self):
        """Number of variants.

        :rtype: long

        """
        try:
            return self.jvds.nVariants()
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def was_split(self):
        """Multiallelic variants have been split into multiple biallelic variants.

        Result is True if :py:meth:`~pyhail.VariantDataset.split_multi` has been called on this dataset
        or the dataset was imported with :py:meth:`~pyhail.HailContext.import_plink`, :py:meth:`~pyhail.HailContext.import_gen`,
        or :py:meth:`~pyhail.HailContext.import_bgen`.

        :rtype: bool

        """
        try:
            return self.jvds.wasSplit()
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def is_dosage(self):
        """Genotype probabilities are dosages.

        The result of ``is_dosage()`` will be True if the dataset was imported with :py:meth:`~pyhail.HailContext.import_gen` or
        :py:meth:`~pyhail.HailContext.import_bgen`.

        :rtype: bool

        """
        try:
            return self.jvds.isDosage()
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def file_version(self):
        """File version of dataset.

        :rtype int

        """
        try:
            return self.jvds.fileVersion()
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def aggregate_by_key(self, key_code, agg_code):
        """Aggregate by user-defined key and aggregation expressions.
        Equivalent of a group-by operation in SQL.

        :param key_code: Named expression(s) for which fields are keys.
        :type key_code: str or list of str

        :param agg_code: Named aggregation expression(s).
        :type agg_code: str or list of str

        :rtype: :class:`.KeyTable`

        """
        try:
            return KeyTable(self.hc, self.jvds.aggregateByKey(key_code, agg_code))
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def aggregate_intervals(self, input, condition, output):
        """Aggregate over intervals and export.

        :param str input: Input interval list file.

        :param str condition: Aggregation expression.

        :param str output: Output file.

        """

        pargs = ['aggregateintervals', '-i', input, '-c', condition, '-o', output]
        return self.hc.run_command(self, pargs)

    def annotate_alleles_expr(self, condition, propagate_gq=False):
        """Annotate alleles with expression.

        :param condition: Annotation expression.
        :type condition: str or list of str
        :param bool propagate_gq: Propagate GQ instead of computing from (split) PL.

        """
        if isinstance(condition, list):
            condition = ','.join(condition)
        pargs = ['annotatealleles', 'expr', '-c', condition]
        if propagate_gq:
            pargs.append('--propagate-gq')
        return self.hc.run_command(self, pargs)

    def annotate_global_expr_by_variant(self, condition):
        """Update the global annotations with expression with aggregation over
        variants.

        :param condition: Annotation expression.
        :type condition: str or list of str

        """

        if isinstance(condition, list):
            condition = ','.join(condition)
        pargs = ['annotateglobal', 'exprbyvariant', '-c', condition]
        return self.hc.run_command(self, pargs)

    def annotate_global_expr_by_sample(self, condition):
        """Update the global annotations with expression with aggregation over
        samples.

        :param str condition: Annotation expression.
        :type condition: str or list of str

        """

        if isinstance(condition, list):
            condition = ','.join(condition)
        pargs = ['annotateglobal', 'exprbysample', '-c', condition]
        return self.hc.run_command(self, pargs)

    def annotate_global_list(self, input, root, as_set=False):
        """Load text file into global annotations as Array[String] or
        Set[String].

        :param str input: Input text file.

        :param str root: Global annotation path to store text file.

        :param bool as_set: If True, load text file as Set[String],
            otherwise, load as Array[String].
        """

        pargs = ['annotateglobal', 'list', '-i', input, '-r', root]
        if as_set:
            pargs.append('--as-set')
        return self.hc.run_command(self, pargs)

    def annotate_global_table(self, input, root, config=None):
        """Load delimited text file (text table) into global annotations
        as an ``Array[Struct]``.

        **Example**

         >>> conf = pyhail.TextTableConfig(impute=True)
         >>> vds = (hc.read('data/example.vds')
         >>>   .annotate_global_table('data/global_table.tsv', 'global.gene_annotations', config=conf))


         **Using the** ``root`` **argument**

         The ``root`` argument determines the annotation path for the
         generated ``Array[Struct]``.  If your table has columns ``GENE``,
          ``PLI``, and ``EXAC_LOF_COUNT``, then ``root='global.genes'``
          creates the an array of structs ``{Sample, Sex, Batch}`` at
          ``global.genes``, which gives you access to the paths like
          ``global.genes[0].GENE``, or ``global.genes.map(s => s.EXAC_LOF_COUNT)``


        :param str input: Input text file.

        :param str root: Global annotation path to store text table.

        :param config: Configuration options for importing text files
        :type config: :class:`.TextTableConfig` or None

        """

        pargs = ['annotateglobal', 'table', '-i', input, '-r', root]

        if not config:
            config = TextTableConfig()

        pargs.extend(config.as_pargs())

        return self.hc.run_command(self, pargs)

    def annotate_samples_expr(self, condition):
        """Annotate samples with expression.

        :param condition: Annotation expression.
        :type condition: str or list of str

        """

        if isinstance(condition, list):
            condition = ','.join(condition)
        pargs = ['annotatesamples', 'expr', '-c', condition]
        return self.hc.run_command(self, pargs)

    def annotate_samples_fam(self, input, quantpheno=False, delimiter='\\\\s+', root='sa.fam', missing='NA'):
        """Import PLINK .fam file into sample annotations.

        :param str input: Path to .fam file.

        :param str root: Sample annotation path to store .fam file.

        :param bool quantpheno: If True, .fam phenotype is interpreted as quantitative.

        :param str delimiter: .fam file field delimiter regex.

        :param str missing: The string used to denote missing values.
            For case-control, 0, -9, and non-numeric are also treated
            as missing.

        """

        pargs = ['annotatesamples', 'fam', '-i', input, '--root', root, '--missing', missing]
        if quantpheno:
            pargs.append('--quantpheno')
        if delimiter:
            pargs.append('--delimiter')
            pargs.append(delimiter)
        return self.hc.run_command(self, pargs)

    def annotate_samples_list(self, input, root):
        """Annotate samples with a Boolean indicating presence/absence in a
        list of samples in a text file.

        :param str input: Sample list file.

        :param str root: Sample annotation path to store Boolean.

        """

        pargs = ['annotatesamples', 'list', '-i', input, '-r', root]
        return self.hc.run_command(self, pargs)

    def annotate_samples_table(self, input, sample_expr, root=None, code=None, config=None):
        """Annotate samples with delimited text file (text table).

        :param str input: Path to delimited text file.

        :param str sample_expr: Expression for sample id (key).

        :param str root: Sample annotation path to store text table.

        :param str code: Annotation expression.

        :param config: Configuration options for importing text files
        :type config: :class:`.TextTableConfig` or None

        """

        pargs = ['annotatesamples', 'table', '-i', input, '--sample-expr', sample_expr]
        if root:
            pargs.append('--root')
            pargs.append(root)
        if code:
            pargs.append('--code')
            pargs.append(code)

        if not config:
            config = TextTableConfig()

        pargs.extend(config.as_pargs())

        return self.hc.run_command(self, pargs)

    def annotate_samples_vds(self, right, root=None, code=None):
        """Annotate samples with sample annotations from .vds file.

        :param VariantDataset right: VariantDataset to annotate with.

        :param str root: Sample annotation path to add sample annotations.

        :param str code: Annotation expression.

        """

        return VariantDataset(
            self.hc,
            self.hc.jvm.org.broadinstitute.hail.driver.AnnotateSamplesVDS.annotate(
                self.jvds, right.jvds, code, root))

    def annotate_variants_bed(self, input, root, all=False):
        """Annotate variants with a .bed file.

        :param str input: Path to .bed file.

        :param str root: Variant annotation path to store annotation.

        :param bool all: If true, store values from all overlapping
            intervals as a set.

        """

        pargs = ['annotatevariants', 'bed', '-i', input, '--root', root]
        if all:
            pargs.append('--all')
        return self.hc.run_command(self, pargs)

    def annotate_variants_expr(self, condition):
        """Annotate variants with expression.

        :param str condition: Annotation expression.

        """
        if isinstance(condition, list):
            condition = ','.join(condition)
        pargs = ['annotatevariants', 'expr', '-c', condition]
        return self.hc.run_command(self, pargs)

    def annotate_variants_intervals(self, input, root, all=False):
        """Annotate variants from an interval list file.

        :param str input: Path to .interval_list.

        :param str root: Variant annotation path to store annotation.

        :param bool all: If true, store values from all overlapping
            intervals as a set.

        """
        pargs = ['annotatevariants', 'intervals', '-i', input, '--root', root]
        if all:
            pargs.append('--all')
        return self.hc.run_command(self, pargs)

    def annotate_variants_loci(self, path, locus_expr, root=None, code=None, config=None):
        """Annotate variants from an delimited text file (text table) indexed
        by loci.

        :param str path: Path to delimited text file.

        :param str locus_expr: Expression for locus (key).

        :param str root: Variant annotation path to store annotation.

        :param str code: Annotation expression.

        :param config: Configuration options for importing text files
        :type config: :class:`.TextTableConfig` or None

        """

        pargs = ['annotatevariants', 'loci', '--locus-expr', locus_expr]

        if root:
            pargs.append('--root')
            pargs.append(root)

        if code:
            pargs.append('--code')
            pargs.append(code)

        if not config:
            config = TextTableConfig()

        pargs.extend(config.as_pargs())

        if isinstance(path, str):
            pargs.append(path)
        else:
            for p in path:
                pargs.append(p)

        return self.hc.run_command(self, pargs)

    def annotate_variants_table(self, path, variant_expr, root=None, code=None, config=None):
        """Annotate variant with delimited text file (text table).

        :param path: Path to delimited text files.
        :type path: str or list of str

        :param str variant_expr: Expression for Variant (key).

        :param str root: Variant annotation path to store text table.

        :param str code: Annotation expression.

        :param config: Configuration options for importing text files
        :type config: :class:`.TextTableConfig` or None

        """

        pargs = ['annotatevariants', 'table', '--variant-expr', variant_expr]

        if root:
            pargs.append('--root')
            pargs.append(root)

        if code:
            pargs.append('--code')
            pargs.append(code)

        if not config:
            config = TextTableConfig()

        pargs.extend(config.as_pargs())

        if isinstance(path, str):
            pargs.append(path)
        else:
            for p in path:
                pargs.append(p)

        return self.hc.run_command(self, pargs)

    def annotate_variants_vds(self, other, code=None, root=None):
        """Annotate variants with variant annotations from .vds file.

        :param VariantDataset other: VariantDataset to annotate with.

        :param str root: Sample annotation path to add variant annotations.

        :param str code: Annotation expression.

        """

        return VariantDataset(
            self.hc,
            self.hc.jvm.org.broadinstitute.hail.driver.AnnotateVariantsVDS.annotate(
                self.jvds, other.jvds, code, root))

    def cache(self):
        """Cache in memory.  cache is the same as persist("MEMORY_ONLY").

           See :py:meth:`~pyhail.VariantDataset.persist` for more detail
           on memory and disk checkpointing.
        """

        pargs = ['cache']
        return self.hc.run_command(self, pargs)

    def concordance(self, right):
        """Calculate call concordance with right.  Performs inner join on
        variants, outer join on samples.

        :return: Returns a pair of VariantDatasets with the sample and
            variant concordance, respectively.

        :rtype: (VariantDataset, VariantData)

        """

        result = self.hc.jvm.org.broadinstitute.hail.driver.Concordance.calculate(
            self.jvds, right.jvds)
        return (VariantDataset(self.hc, result._1()),
                VariantDataset(self.hc, result._2()))

    def count(self, genotypes=False):
        """Return number of samples, varaints and genotypes.

        :param bool genotypes: If True, return number of called
            genotypes and genotype call rate.

        """

        try:
            return (scala_package_object(self.hc.jvm.org.broadinstitute.hail.driver)
                    .count(self.jvds, genotypes)
                    .toJavaMap())
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def deduplicate(self):
        """Remove duplicate variants."""

        pargs = ['deduplicate']
        return self.hc.run_command(self, pargs)

    def downsample_variants(self, keep):
        """Downsample variants.

        :param int keep: (Expected) number of variants to keep.

        """

        pargs = ['downsamplevariants', '--keep', str(keep)]
        return self.hc.run_command(self, pargs)

    def export_gen(self, output):
        """Export dataset as .gen file.

        :param str output: Output file base.  Will write .gen and .sample files.

        """

        pargs = ['exportgen', '--output', output]
        return self.hc.run_command(self, pargs)

    def export_genotypes(self, output, condition, types=None, export_ref=False, export_missing=False):
        """Export genotype information (variant- and sample-index) information
        to delimited text file.

        :param str output: Output path.

        :param str condition: Annotation expression for values to export.

        :param types: Path to write types of exported values.
        :type types: str or None

        :param bool export_ref: If True, export reference genotypes.

        :param bool export_missing: If True, export missing genotypes.

        """

        pargs = ['exportgenotypes', '--output', output, '-c', condition]
        if types:
            pargs.append('--types')
            pargs.append(types)
        if export_ref:
            pargs.append('--print-ref')
        if export_missing:
            pargs.append('--print-missing')
        return self.hc.run_command(self, pargs)

    def export_plink(self, output, fam_expr='id = s.id'):
        """Export as PLINK .bed/.bim/.fam

        **Example**

        >>> vds.export_plink('data/plink')

        Hail's output is designed to mirror Plink's own VCF conversion
        using the following command:
```
plink --vcf /path/to/file.vcf --make-bed --out sample --const-fid --keep-allele-order
```

All differences between Hail's output and Plink's are enumerated below.
 - **.bed file**: equivalent within multiallelic split variant ordering
 - **.fam file**: agrees when Plink is run with `--const-fid` argument (FID is set to "0")
 - **.bim file**: ID field will be different.  The above Plink command will copy the "RSID" field of the VCF into the .bim ID column, leaving huge numbers of IDs as ".".  Instead, Hail will encode each variant with an ID in the format "CHR:POS:REF:ALT".


        :param str output: Output file base.  Will write .bed, .bim and .fam files.

        :param str fam_expr: expression to generate FID in resulting .fam file

        """

        pargs = ['exportplink', '--output', output, '--fam-expr', fam_expr]
        return self.hc.run_command(self, pargs)

    def export_samples(self, output, condition, types=None):
        """Export sample information to delimited text file.


        **Example**

        Export a TSV with sample ID and 5 computed principal components.

        >>> vds.export_samples('data/pca.tsv',
        >>>   'ID = s.id, PC1 = sa.pca.PC1, PC2 = sa.pca.PC2,'
        >>>   'PC3 =, sa.pca.PC3, PC4 = sa.pca.PC4, PC5 = sa.pca.PC5')

        Or use the "splat" star syntax to produce the same result:

        >>> vds.export_samples('data/pca.tsv',
        >>>   'ID = s.id, sa.pca.*')


        It is also possible to export without identifiers, which will result in
        a file with no header. In this case, the expressions should look like
        the example below:

        >>> vds.export_samples('data/file.tsv', 's.id, sa.qc.gqMean')

        *Note:* Either all fields must be named, or no field must be named.

        In the common case that a group of annotations needs to be exported (for
        example, the annotations produced by ``sampleqc``), one can use the
        ``struct.*`` syntax.  This syntax produces one column per field in the
        struct, and names them according to the struct field name.

        For example, the following invocation (assuming ``sa.qc`` was generated
        by :py:meth:`.sample_qc`):

        >>> vds.export_samples('data/file.tsv', 'sample = s.id, sa.qc.*')

        will produce the following set of columns::

            sample  callRate  nCalled  nNotCalled  nHomRef  ...

        Note that using the ``.*`` syntax always results in named arguments, so it
        is not possible to export header-less files in this manner.  However,
        naming the "splatted" struct will apply the name in front of each column
        like so:

        >>> vds.export_samples('data/file.tsv', 'sample = s.id, QC = sa.qc.*')

        which produces these columns::

            sample  QC.callRate  QC.nCalled  QC.nNotCalled  QC.nHomRef  ...


        **Notes**

        This module takes a comma-delimited list of fields or expressions to
        print. These fields will be printed in the order they appear in the
        expression in the header and on each line.

        One line per sample in the VDS will be printed.  The accessible namespace includes:

        - ``s.id`` (sample ID)
        - ``sa`` (sample annotations)
        - ``global`` (global annotations)
        - ``gs`` (genotype column `aggregable <reference.html#aggregables>`_)

        **Designating output with an expression**

        Much like the filtering methods, exporting allows flexible expressions
        to be written on the command line. While the filtering methods expect an
        expression that evaluates to true or false, this method expects a
        comma-separated list of fields to print. These fields *must* take the
        form ``IDENTIFIER = <expression>``.

        :param str output: Output file.

        :param str condition: Export expression to determine column names and computations.

        :param types: Path to write types of exported values.
        :type types: str or None

        """

        pargs = ['exportsamples', '--output', output, '-c', condition]
        if types:
            pargs.append('--types')
            pargs.append(types)
        return self.hc.run_command(self, pargs)

    def export_variants(self, output, condition, types=None):
        """Export variant information to delimited text file.

        :param str output: Output file.

        :param str condition: Annotation expression for values to export.

        :param types: Path to write types of exported values.
        :type types: str or None

        """

        pargs = ['exportvariants', '--output', output, '-c', condition]
        if types:
            pargs.append('--types')
            pargs.append(types)
        return self.hc.run_command(self, pargs)

    def export_variants_cass(self, variant_condition, genotype_condition,
                             address,
                             keyspace,
                             table,
                             export_missing=False,
                             export_ref=False):
        """Export variant information to Cassandra."""

        pargs = ['exportvariantscass', '-v', variant_condition, '-g', genotype_condition,
                 '-a', address, '-k', keyspace, '-t', table]
        if export_missing:
            pargs.append('--export-missing')
        if export_ref:
            pargs.append('--export-ref')
        return self.hc.run_command(self, pargs)

    def export_variants_solr(self, variant_condition, genotype_condition,
                             solr_url=None,
                             solr_cloud_collection=None,
                             zookeeper_host=None,
                             drop=False,
                             num_shards=1,
                             export_missing=False,
                             export_ref=False,
                             block_size=100):
        """Export variant information to Cassandra."""

        pargs = ['exportvariantssolr', '-v', variant_condition, '-g', genotype_condition, '--block-size', block_size]
        if solr_url:
            pargs.append('-u')
            pargs.append(solr_url)
        if solr_cloud_collection:
            pargs.append('-c')
            pargs.append(solr_cloud_collection)
        if zookeeper_host:
            pargs.append('-z')
            pargs.append(zookeeper_host)
        if drop:
            pargs.append('--drop')
        if num_shards:
            pargs.append('--num-shards')
            pargs.append(num_shards)
        if export_missing:
            pargs.append('--export-missing')
        if export_ref:
            pargs.append('--export-ref')
        return self.hc.run_command(self, pargs)

    def export_vcf(self, output, append_to_header=None, export_pp=False, parallel=False):
        """Export as .vcf file.

        :param str output: Path of .vcf file to write.

        :param append_to_header: Path of file to append to .vcf header.
        :type append_to_header: str or None

        :param bool export_pp: If True, export Hail pl genotype field as VCF PP FORMAT field.

        :param bool parallel: If True, export .vcf in parallel.

        """

        pargs = ['exportvcf', '--output', output]
        if append_to_header:
            pargs.append('-a')
            pargs.append(append_to_header)
        if export_pp:
            pargs.append('--export-pp')
        if parallel:
            pargs.append('--parallel')
        return self.hc.run_command(self, pargs)

    def write(self, output, overwrite=False):
        """Write as .vds file.

        :param str output: Path of .vds file to write.

        :param bool overwrite: If True, overwrite any existing .vds file.

        """

        pargs = ['write', '-o', output]
        if overwrite:
            pargs.append('--overwrite')
        return self.hc.run_command(self, pargs)

    def filter_alleles(self, condition, annotation=None, subset=True, keep=True, filter_altered_genotypes=False):
        """
        Filter a user-defined set of alternate alleles for each variant.
        If all of a variant's alternate alleles are filtered, the variant itself is filtered.
        The condition expression is evaluated for each alternate allele.

        :param condition: Filter expression involving v (variant), va (variant annotations), and aIndex (allele index)
        :param annotation: Annotation modifying expression involving v (new variant), va (old variant annotations),
            and aIndices (maps from new to old indices) (default: "va = va")
        :param bool subset: If true, subsets the PL and AD, otherwise downcodes the PL and AD.
            Genotype and GQ are set based on the resulting PLs.
        :param bool keep: Keep variants matching condition
        :param bool filter_altered_genotypes: If set, any genotype call that would change due to filtering an allele
            would be set to missing instead.

        """

        pargs = ['filteralleles',
                 '--keep' if keep else '--remove',
                 '--subset' if subset else '--downcode',
                 '-c', condition]
        if annotation:
            pargs.extend(['-a', annotation])
        if filter_altered_genotypes:
            pargs.append('--filterAlteredGenotypes')
        return self.hc.run_command(self, pargs)

    def filter_genotypes(self, condition, keep=True):
        """Filter variants based on expression.

        :param str condition: Expression for filter condition.

        """

        pargs = ['filtergenotypes',
                 '--keep' if keep else '--remove',
                 '-c', condition]
        return self.hc.run_command(self, pargs)

    def filter_multi(self):
        """Filter out multi-allelic sites.

        Returns a VariantDataset with split = True.

        """

        pargs = ['filtermulti']
        return self.hc.run_command(self, pargs)

    def filter_samples_all(self):
        """Discard all samples (and genotypes)."""

        pargs = ['filtersamples', 'all']
        return self.hc.run_command(self, pargs)

    def filter_samples_expr(self, condition, keep=True):
        """Filter samples based on expression.

        :param condition: Expression for filter condition.
        :type condition: str or list of str

        """

        if isinstance(condition, list):
            condition = ','.join(condition)
        pargs = ['filtersamples', 'expr',
                 '--keep' if keep else '--remove',
                 '-c', condition]
        return self.hc.run_command(self, pargs)

    def filter_samples_list(self, input, keep=True):
        """Filter samples with a sample list file.

        :param str input: Path to sample list file.

        """

        pargs = ['filtersamples', 'list',
                 '--keep' if keep else '--remove',
                 '-i', input]
        return self.hc.run_command(self, pargs)

    def filter_variants_all(self):
        """Discard all variants, variant annotations and genotypes."""

        pargs = ['filtervariants', 'all']
        return self.hc.run_command(self, pargs)

    def filter_variants_expr(self, condition, keep=True):
        """Filter variants based on expression.

        :param condition: Expression for filter condition.
        :type condition: str or list of str

        """

        if isinstance(condition, list):
            condition = ','.join(condition)
        pargs = ['filtervariants', 'expr',
                 '--keep' if keep else '--remove',
                 '-c', condition]
        return self.hc.run_command(self, pargs)

    def filter_variants_intervals(self, input, keep=True):
        """Filter variants with an .interval_list file.

        :param str input: Path to .interval_list file.

        """

        pargs = ['filtervariants', 'intervals',
                 '--keep' if keep else '--remove',
                 '-i', input]
        return self.hc.run_command(self, pargs)

    def filter_variants_list(self, input, keep=True):
        """Filter variants with a list of variants.

        :param str input: Path to variant list file.

        """

        pargs = ['filtervariants', 'list',
                 '--keep' if keep else '--remove',
                 '-i', input]
        return self.hc.run_command(self, pargs)

    def grm(self, format, output, id_file=None, n_file=None):
        """Compute the Genetic Relatedness Matrix (GMR).

        :param str format: Output format.  One of: rel, gcta-grm, gcta-grm-bin.

        :param str id_file: ID file.

        :param str n_file: N file, for gcta-grm-bin only.

        :param str output: Output file.

        """

        pargs = ['grm', '-f', format, '-o', output]
        if id_file:
            pargs.append('--id-file')
            pargs.append(id_file)
        if n_file:
            pargs.append('--N-file')
            pargs.append(n_file)
        return self.hc.run_command(self, pargs)

    def hardcalls(self):
        """Drop all genotype fields except the GT field."""

        pargs = ['hardcalls']
        return self.hc.run_command(self, pargs)

    def ibd(self, output, maf=None, unbounded=False, min=None, max=None):
        """Compute matrix of identity-by-descent estimations.

        :param str output: Output .tsv file for IBD matrix.

        :param maf: Expression for the minor allele frequency.
        :type maf: str or None

        :param bool unbounded: Allows the estimations for Z0, Z1, Z2,
            and PI_HAT to take on biologically-nonsense values
            (e.g. outside of [0,1]).

        :param min: "Sample pairs with a PI_HAT below this value will
            not be included in the output. Must be in [0,1].
        :type min: float or None

        :param max: Sample pairs with a PI_HAT above this value will
            not be included in the output. Must be in [0,1].
        :type max: float or None

        """

        pargs = ['ibd', '-o', output]
        if maf:
            pargs.append('-m')
            pargs.append(maf)
        if unbounded:
            pargs.append('--unbounded')
        if min:
            pargs.append('--min')
            pargs.append(min)
        if max:
            pargs.append('--min')
            pargs.append(max)
        return self.hc.run_command(self, pargs)

    def impute_sex(self, maf_threshold=0.0, include_par=False, female_threshold=0.2, male_threshold=0.8, pop_freq=None):
        """Impute sex of samples by calculating inbreeding coefficient on the
        X chromosome.

        :param float maf_threshold: Minimum minor allele frequency threshold.

        :param bool include_par: Include pseudoautosomal regions.

        :param float female_threshold: Samples are called females if F < femaleThreshold

        :param float male_threshold: Samples are called males if F > maleThreshold

        :param str pop_freq: Variant annotation for estimate of MAF.
            If None, MAF will be computed.

        """

        pargs = ['imputesex']
        if maf_threshold:
            pargs.append('--maf-threshold')
            pargs.append(str(maf_threshold))
        if include_par:
            pargs.append('--include_par')
        if female_threshold:
            pargs.append('--female-threshold')
            pargs.append(str(female_threshold))
        if male_threshold:
            pargs.append('--male-threshold')
            pargs.append(str(male_threshold))
        if pop_freq:
            pargs.append('--pop-freq')
            pargs.append(pop_freq)
        return self.hc.run_command(self, pargs)

    def join(self, right):
        """Join datasets, inner join on variants, concatenate samples, variant
        and global annotations from self.

        """
        try:
            return VariantDataset(self.hc, self.hc.jvm.org.broadinstitute.hail.driver.Join.join(self.jvds, right.jvds))
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def linreg(self, y, covariates='', root='va.linreg', minac=1, minaf=None):
        """Test each variant for association using the linear regression
        model.

        :param str y: Response sample annotation.

        :param str covariates: Covariant sample annotations, comma separated.

        :param str root: Variant annotation path to store result of linear regression.

        :param float minac: Minimum alternate allele count.

        :param minaf: Minimum alternate allele frequency.
        :type minaf: float or None

        """

        pargs = ['linreg', '-y', y, '-c', covariates, '-r', root, '--mac', str(minac)]
        if minaf:
            pargs.append('--maf')
            pargs.append(str(minaf))
        return self.hc.run_command(self, pargs)

    def logreg(self, test, y, covariates=None, root=None):
        r"""Test each variant for association using the logistic regression
        model.

        The `logreg` command performs, for each variant, a significance
        test of the genotype in predicting a binary (case-control)
        phenotype based on the logistic regression model. Hail supports
        the Wald test, likelihood ratio test (LRT), and Rao score test.
        Hail only includes samples for which phenotype and all covariates
        are defined. For each variant, Hail imputes missing genotypes as
        the mean of called genotypes.

        Assuming there are sample annotations 'sa.pheno.isCase',
        'sa.cov.age', 'sa.cov.isFemale', and 'sa.cov.PC1', the command:

        >>> vds.logreg('wald', 'sa.pheno.isCase', covariates='sa.cov.age,sa.cov.isFemale,sa.cov.PC1')

        considers a model of the form

        .. math::
        \mathrm{Prob}(\mathrm{isCase}) = \mathrm{sigmoid}(\beta_0 + \beta_1 \, \mathrm{gt} + \beta_2 \, \mathrm{age} + \beta_3 \, \mathrm{isMale} + \beta_4 \, \mathrm{PC1} + \varepsilon), \quad \varepsilon \sim \mathrm{N}(0, \sigma^2)

        where :math:`\mathrm{sigmoid}` is the ` sigmoid function <https://en.wikipedia.org/wiki/Sigmoid_function>`_,
        the genotype :math:`\mathrm{gt}` is coded as :math:`0` for HomRef, :math:`1` for
        Het, and :math:`2` for HomVar, and the Boolean covariate :math:`\mathrm{isFemale}`
        is coded as :math:`1` for true (female) and :math:`0` for false (male). The null
        model sets :math:`\beta_1 = 0`.

        The resulting variant annotations depend on the test statistic as shown in
        the tables below. These annotations can then be accessed by other methods,
        including exporting to TSV with other variant annotations.

        **Annotations**

        ``Wald`` adds the following annotations:

         - **va.logreg.wald.beta** (*Double*) -- fit genotype coefficient, :math:`\hat\beta_1`

         - **va.logreg.wald.se** (*Double*) -- estimated standard error, :math:`\widehat{\mathrm{se}}`

         - **va.logreg.wald.zstat** (*Double*) -- Wald :math:`z`-statistic,
           equal to :math:`\hat\beta_1 / \widehat{\mathrm{se}}`

         - **va.logreg.wald.pval** (*Double*) -- Wald test :math:`p`-value
           testing :math:`\beta_1 = 0`

         - **va.logreg.wald.pval** (*Double*) -- Wald test :math:`p`-value
           testing :math:`\beta_1 = 0`

         - **va.logreg.fit.nIter** (*Int*) -- number of iterations
           until convergence, explosion, or reaching the max (25)

         - **va.logreg.fit.converged** (*Boolean*) -- true if
           iteration converged

         - **va.logreg.fit.exploded** (*Boolean*) -- true if
           iteration exploded

        ``LRT`` adds the following annotations:

         - **va.logreg.lrt.beta** (*Double*) -- fit genotype coefficient, :math:`\hat\beta_1`

         - **va.logreg.lrt.chi2** (*Double*) -- likelihood ratio
           test statistic (deviance) testing :math:`\beta_1 = 0`

         - **va.logreg.lrt.pval** (*Double*) -- likelihood ratio
           test :math:`p`-value

         - **va.logreg.fit.nIter** (*Int*) -- number of iterations
           until convergence, explosion, or reaching the max (25)

         - **va.logreg.fit.converged** (*Boolean*) -- true if
           iteration converged

         - **va.logreg.fit.exploded** (*Boolean*) -- true if
           iteration exploded

        ``Score`` adds the following annotations:

         - **va.logreg.score.chi2** (*Double*) -- score statistic
           testing :math:`\beta_1 = 0`

         - **va.logreg.score.pval** (*Double*) -- score test
           :math:`p`-value

        We consider iteration to have converged when every coordinate of $\beta$
        changes by less than $10^{-6}$. Up to 25 iterations are attempted; in
        testing we find 4 or 5 iterations nearly always suffice. Convergence may
        also fail due to explosion, which refers to low-level numerical linear
        algebra exceptions caused by manipulating ill-conditioned matrices.
        Explosion may result from (nearly) linearly dependent covariates or
        complete `separation <https://en.wikipedia.org/wiki/Separation_(statistics)>`_.

        A more common situation in genetics is quasi-complete seperation, e.g.
        variants that are observed only in cases (or controls). Such variants
        inevitably arise when testing millions of variants with very low minor
        allele count. The maximum likelihood estimate of $\beta$ under logistic
        regression is then undefined but convergence may still occur after a
        large number of iterations due to a very flat likelihood surface. In
        testing, we find that such variants produce a secondary bump from 10 to
        15 iterations in the histogram of number of iterations per variant. We
        also find that this faux convergence produces large standard errors and
        large (insignificant) :math:`p`-values. To not miss such variants,
        consider using Firth logistic regression, linear regression, or
        group-based tests.

        Here's a concrete illustration of quasi-complete seperation in R.
        Suppose we have 2010 samples distributed as follows for a particular variant:

        \- | HomRef | Het | HomVar
        ---|---|---|---
        Case | 1000 | 10 | 0
        Control | 1000 | 0 | 0

The following R code fits the (standard) logistic, Firth logistic, and linear regression models to this data, where $x$ is genotype, $y$ is phenotype, and `logistf` is from the `logistf` package.
```
x <- c(rep(0,1000), rep(1,1000), rep(1,10)
y <- c(rep(0,1000), rep(0,1000), rep(1,10))
logfit <- glm(y ~ x, family=binomial())
firthfit <- logistf(y ~ x)
linfit <- lm(y ~ x)
```
The resulting $p$-values for the genotype coefficient are $0.991$, $0.00085$, and $0.0016$, respectively. The erroneous value $0.991$ is due to quasi-complete separation. Moving one of the 10 hets from case to control eliminates this quasi-complete separation; the p-values from R are then $0.0373$, $0.0111$, and $0.0116$, respectively, as expected for a less significant association.

Phenotype and covariate sample annotations may also be specified using [programmatic expressions](reference.html#HailExpressionLanguage) without identifiers, such as
```
if (sa.isFemale) sa.cov.age else (2 * sa.cov.age + 10)
```
For Boolean covariate types, true is coded as 1 and false as 0. In particular, for the sample annotation `sa.fam.isCase` added by importing a `.fam` file with case-control phenotype, case is $1$ and control is $0$.

Hail's logistic regression tests correspond to the `b.wald`, `b.lrt`, and `b.score` tests in [EPACTS](http://genome.sph.umich.edu/wiki/EPACTS#Single_Variant_Tests). For each variant, Hail imputes missing genotypes as the mean of called genotypes, whereas EPACTS subsets to those samples with called genotypes. Hence, Hail and EPACTS results will currently only agree for variants with no missing genotypes.

See [Recommended joint and meta-analysis strategies for case-control association testing of single low-count variants](http://www.ncbi.nlm.nih.gov/pmc/articles/PMC4049324/) for an empirical comparison of the logistic Wald, LRT, score, and Firth tests. The theoretical foundations of the Wald, likelihood ratio, and score tests may be found in Chapter 3 of Gesine Reinert's notes [Statistical Theory](http://www.stats.ox.ac.uk/~reinert/stattheory/theoryshort09.pdf).
</div>


        :param str test: Statistical test, one of: wald, lrt, or score.

        :param str y: Response sample annotation.  Must be Boolean or
            numeric with all values 0 or 1.

        :param str covariates: Covariant sample annotations, comma separated.

        :param str root: Variant annotation path to store result of linear regression.

        """

        pargs = ['logreg', '-t', test, '-y', y]
        if covariates:
            pargs.append('-c')
            pargs.append(covariates)
        if root:
            pargs.append('-r')
            pargs.append(root)
        return self.hc.run_command(self, pargs)

    def mendel_errors(self, output, fam):
        """Find Mendel errors; count per variant, individual and nuclear
        family.

        :param str output: Output root filename.

        :param str fam: Path to .fam file.

        """

        pargs = ['mendelerrors', '-o', output, '-f', fam]
        return self.hc.run_command(self, pargs)

    def pca(self, scores, loadings=None, eigenvalues=None, k=10, arrays=False):
        """Run Principal Component Analysis (PCA) on the matrix of genotypes.

        :param str scores: Sample annotation path to store scores.

        :param loadings: Variant annotation path to store site loadings
        :type loadings: str or None

        :param eigenvalues: Global annotation path to store eigenvalues.
        :type eigenvalues: str or None

        """

        pargs = ['pca', '--scores', scores, '-k', str(k)]
        if loadings:
            pargs.append('--loadings')
            pargs.append(loadings)
        if eigenvalues:
            pargs.append('--eigenvalues')
            pargs.append(eigenvalues)
        if arrays:
            pargs.append('--arrays')
        return self.hc.run_command(self, pargs)

    def persist(self, storage_level="MEMORY_AND_DISK"):
        """Persist the current dataset.

        :param storage_level: Storage level.  One of: NONE, DISK_ONLY,
            DISK_ONLY_2, MEMORY_ONLY, MEMORY_ONLY_2, MEMORY_ONLY_SER,
            MEMORY_ONLY_SER_2, MEMORY_AND_DISK, MEMORY_AND_DISK_2,
            MEMORY_AND_DISK_SER, MEMORY_AND_DISK_SER_2, OFF_HEAP

        """

        pargs = ['persist']
        if storage_level:
            pargs.append('-s')
            pargs.append(storage_level)
        return self.hc.run_command(self, pargs)

    def print_schema(self, output=None, attributes=False, va=False, sa=False, print_global=False):
        """Shows the schema for global, sample and variant annotations.

        :param output: Output file.
        :type output: str or None

        :param bool attributes: If True, print attributes.

        :param bool va: If True, print variant annotations schema.

        :param bool sa: If True, print sample annotations schema.

        :param bool print_global: If True, print global annotations schema.

        """

        pargs = ['printschema']
        if output:
            pargs.append('--output')
            pargs.append(output)
        if attributes:
            pargs.append('--attributes')
        if va:
            pargs.append('--va')
        if sa:
            pargs.append('--sa')
        if print_global:
            pargs.append('--global')
        return self.hc.run_command(self, pargs)

    def rename_samples(self, input):
        """Rename samples.

        :param str input: Input file.

        """

        pargs = ['renamesamples', '-i', input]
        return self.hc.run_command(self, pargs)

    def repartition(self, npartition, shuffle=True):
        """Increase or decrease the dataset sharding.  Can improve performance
        after large filters.

        :param int npartition: Number of partitions.

        :param bool shuffle: If True, shuffle to repartition.

        """

        pargs = ['repartition', '--partitions', str(npartition)]
        if not shuffle:
            pargs.append('--no-shuffle')
        return self.hc.run_command(self, pargs)

    def same(self, other):
        """Compare two VariantDatasets.

        :rtype: bool

        """
        try:
            return self.jvds.same(other.jvds, 1e-6)
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def sample_qc(self, branching_factor=None):
        """Compute per-sample QC metrics.

        :param branching_factor: Branching factor to use in tree aggregate.
        :type branching_factor: int or None

        """

        pargs = ['sampleqc']
        if branching_factor:
            pargs.append('-b')
            pargs.append(branching_factor)
        return self.hc.run_command(self, pargs)

    def show_globals(self, output=None):
        """Print or export all global annotations as JSON

        :param output: Output file.
        :type output: str or None

        """

        pargs = ['showglobals']
        if output:
            pargs.append('-o')
            pargs.append(output)
        return self.hc.run_command(self, pargs)

    def sparkinfo(self):
        """Displays the number of partitions and persistence level of the
        dataset."""

        return self.hc.run_command(self, ['sparkinfo'])

    def split_multi(self, propagate_gq=False, keep_star_alleles=False):
        """Split multiallelic variants.

        **Examples**

        >>> (hc.import_vcf('data/sample.vcf')
        >>>  .split_multi()
        >>>  .write('data/split.vds'))

        **Implementation Details**

        We will explain by example. Consider a hypothetical 3-allelic
        variant::

          A   C,T 0/2:7,2,6:15:45:99,50,99,0,45,99

        split_multi will create two biallelic variants (one for each
        alternate allele) at the same position::

          A   C   0/0:13,2:15:45:0,45,99
          A   T   0/1:9,6:15:50:50,0,99

        Each multiallelic GT field is downcoded once for each
        alternate allele. A call for an alternate allele maps to 1 in
        the biallelic variant corresponding to itself and 0
        otherwise. For example, in the example above, 0/2 maps to 0/0
        and 0/1. The genotype 1/2 maps to 0/1 and 0/1.

        The biallelic alt AD entry is just the multiallelic AD entry
        corresponding to the alternate allele. The ref AD entry is the
        sum of the other multiallelic entries.

        The biallelic DP is the same as the multiallelic DP.

        The biallelic PL entry for for a genotype g is the minimum
        over PL entries for multiallelic genotypes that downcode to
        g. For example, the PL for (A, T) at 0/1 is the minimum of the
        PLs for 0/1 (50) and 1/2 (45), and thus 45.

        Fixing an alternate allele and biallelic variant, downcoding
        gives a map from multiallelic to biallelic alleles and
        genotypes. The biallelic AD entry for an allele is just the
        sum of the multiallelic AD entries for alleles that map to
        that allele. Similarly, the biallelic PL entry for a genotype
        is the minimum over multiallelic PL entries for genotypes that
        map to that genotype.

        By default, GQ is recomputed from PL. If ``propagate_gq=True``
        is passed, the biallelic GQ field is simply the multiallelic
        GQ field, that is, genotype qualities are unchanged.

        Here is a second example for a het non-ref::

          A   C,T 1/2:2,8,6:16:45:99,50,99,45,0,99

        splits as::

          A   C   0/1:8,8:16:45:45,0,99
          A   T   0/1:10,6:16:50:50,0,99

        **VCF Info Fields**

        Hail does not split annotations in the info field. This means
        that if a multiallelic site with ``info.AC`` value ``[10, 2]`` is
        split, each split site will contain the same array ``[10,
        2]``. The provided allele index annotation ``va.aIndex`` can be used
        to select the value corresponding to the split allele's
        position:

        >>> (hc.import_vcf('data/sample.vcf')
        >>>  .split_multi()
        >>>  .filter_variants_expr('va.info.AC[va.aIndex - 1] < 10', keep = False))

        VCFs split by Hail and exported to new VCFs may be
        incompatible with other tools, if action is not taken
        first. Since the "Number" of the arrays in split multiallelic
        sites no longer matches the structure on import ("A" for 1 per
        allele, for example), Hail will export these fields with
        number ".".

        If the desired output is one value per site, then it is
        possible to use annotatevariants expr to remap these
        values. Here is an example:

        >>> (hc.import_vcf('data/sample.vcf')
        >>>  .split_multi()
        >>>  .annotate_variants_expr('va.info.AC = va.info.AC[va.aIndex - 1]')
        >>>  .export_vcf('data/export.vcf'))

        The info field AC in ``data/export.vcf`` will have ``Number=1``.

        **Annotations**

        ``split_multi`` adds the following annotations:

         - **va.wasSplit** (*Boolean*) -- true if this variant was
           originally multiallelic, otherwise false.

         - **va.aIndex** (*Int*) -- The original index of this
           alternate allele in the multiallelic representation (NB: 1
           is the first alternate allele or the only alternate allele
           in a biallelic variant). For example, 1:100:A:T,C splits
           into two variants: 1:100:A:T with ``aIndex = 1`` and
           1:100:A:C with ``aIndex = 2``.

        :param bool propagate_gq: Set the GQ of output (split)
          genotypes to be the GQ of the input (multi-allelic) variants
          instead of recompute GQ as the difference between the two
          smallest PL values.  Intended to be used in conjunction with
          ``import_vcf(store_gq=True)``.  This option will be obviated
          in the future by generic genotype schemas.  Experimental.

        :param bool keep_star_alleles: Do not filter out * alleles.

        :return: A VariantDataset of biallelic variants with split set
          to true.

        :rtype: VariantDataset

        """

        pargs = ['splitmulti']
        if propagate_gq:
            pargs.append('--propagate-gq')
        if keep_star_alleles:
            pargs.append('--keep-star-alleles')
        return self.hc.run_command(self, pargs)

    def tdt(self, fam, root='va.tdt'):
        """Find transmitted and untransmitted variants; count per variant and
        nuclear family.

        :param str fam: Path to .fam file.

        :param root: Variant annotation root to store TDT result.

        """

        pargs = ['tdt', '--fam', fam, '--root', root]
        return self.hc.run_command(self, pargs)

    def typecheck(self):
        """Check if all sample, variant and global annotations are consistent
        with the schema.

        """

        pargs = ['typecheck']
        return self.hc.run_command(self, pargs)

    def variant_qc(self):
        """Compute per-variant QC metrics."""

        pargs = ['variantqc']
        return self.hc.run_command(self, pargs)

    def vep(self, config, block_size=None, root=None, force=False, csq=False):
        """Annotate variants with VEP.

        :param str config: Path to VEP configuration file.

        :param block_size: Number of variants to annotate per VEP invocation.
        :type block_size: int or None

        :param str root: Variant annotation path to store VEP output.

        :param bool force: If true, force VEP annotation from scratch.

        :param bool csq: If True, annotates VCF CSQ field as a String.
            If False, annotates with the full nested struct schema

        """

        pargs = ['vep', '--config', config]
        if block_size:
            pargs.append('--block-size')
            pargs.append(block_size)
        if root:
            pargs.append('--root')
            pargs.append(root)
        if force:
            pargs.append('--force')
        if csq:
            pargs.append('--csq')
        return self.hc.run_command(self, pargs)

    def variants_keytable(self):
        """Convert variants and variant annotations to a KeyTable."""

        try:
            return KeyTable(self.hc, self.jvds.variantsKT())
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def samples_keytable(self):
        """Convert samples and sample annotations to KeyTable."""

        try:
            return KeyTable(self.hc, self.jvds.samplesKT())
        except Py4JJavaError as e:
            self._raise_py4j_exception(e)

    def make_keytable(self, variant_condition, genotype_condition, key_names):
        """Make a KeyTable with one row per variant.

        Per sample field names in the result are formed by concatening
        the sample ID with the genotype_condition left hand side with
        dot (.).  If the left hand side is empty::

          `` = expr

        then the dot (.) is ommited.

        **Example**

        Consider a ``VariantDataset`` ``vds`` with 2 variants and 3 samples::

          Variant	FORMAT	A	B	C
          1:1:A:T	GT:GQ	0/1:99	./.	0/0:99
          1:2:G:C	GT:GQ	0/1:89	0/1:99	1/1:93

        Then::

          >>> vds = hc.import_vcf('data/sample.vcf')
          >>> vds.make_keytable('v = v', 'gt = g.gt', gq = g.gq', [])

        returns a ``KeyTable`` with schema::

          v: Variant
          A.gt: Int
          B.gt: Int
          C.gt: Int
          A.gq: Int
          B.gq: Int
          C.gq: Int

        in particular, the values would be::

          v	A.gt	B.gt	C.gt	A.gq	B.gq	C.gq
          1:1:A:T	1	NA	0	99	NA	99
          1:2:G:C	1	1	2	89	99	93

        :param variant_condition: Variant annotation expressions.
        :type variant_condition: str or list of str

        :param genotype_condition: Genotype annotation expressions.
        :type genotype_condition: str or list of str

        :param key_names: list of key columns
        :type key_names: list of str

        :rtype: KeyTable

        """

        if isinstance(variant_condition, list):
            variant_condition = ','.join(variant_condition)
        if isinstance(genotype_condition, list):
            genotype_condition = ','.join(genotype_condition)

        jkt = (scala_package_object(self.hc.jvm.org.broadinstitute.hail.driver)
               .makeKT(self.jvds, variant_condition, genotype_condition,
                       jarray(self.hc.gateway, self.hc.jvm.java.lang.String, key_names)))
        return KeyTable(self.hc, jkt)
