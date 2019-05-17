package is.hail.expr.ir

import is.hail.annotations.aggregators._
import is.hail.asm4s._
import is.hail.expr.types.physical.PTuple
import is.hail.expr.types.virtual._
import is.hail.utils._

import scala.language.{existentials, postfixOps}
import scala.reflect.ClassTag

case class StagedExtractedAggregators(postAggIR: IR, resultType: PTuple, init: IR, perElt: IR, rvAggs: Code[Array[RegionValueAggregator]])

object StagedExtractAggregators {

  private case class IRAgg(i: Int, rvAgg: Code[RegionValueAggregator], rt: Type)

  private case class AggOps(initOp: Option[IR], seqOp: IR)

  def newArray[T](fb: EmitFunctionBuilder[_], a: Array[Code[T]])(implicit tct: ClassTag[T], tti: TypeInfo[T]): Code[Array[T]] = {
    val sa = fb.newField[Array[T]]("a")
    Code(
      sa := Code.newArray[T](const(a.length)),
      Code(a.zipWithIndex.map { case (ai, i) => sa.load().update(const(i), ai) }: _*),
      sa.load())
  }

  def apply(fb: EmitFunctionBuilder[_], ir: IR, resultName: String = "AGGR"): StagedExtractedAggregators = {
    val ab = new ArrayBuilder[IRAgg]()
    val ab2 = new ArrayBuilder[AggOps]()
    val ab3 = new ArrayBuilder[AggLet]()
    val ref = Ref(resultName, null)
    val postAgg = extract(fb, ir, ab, ab2, ab3, ref)
    val aggs = ab.result()
    val aggLets = ab3.result()
    assert(aggLets.areDistinct())
    val rt = TTuple(aggs.map(_.rt): _*)
    ref._typ = rt
    val ops = ab2.result()
    StagedExtractedAggregators(
      postAgg,
      rt.physicalType,
      Begin(ops.flatMap(_.initOp)),
      aggLets.foldRight[IR](Begin(ops.map(_.seqOp))) { case (al, comb) => Let(al.name, al.value, comb)},
      newArray(fb, aggs.map(_.rvAgg)))
  }

  private def extract(fb: EmitFunctionBuilder[_], ir: IR, ab: ArrayBuilder[IRAgg], ab2: ArrayBuilder[AggOps], ab3: ArrayBuilder[AggLet], result: IR): IR = {
    def extract(node: IR): IR = this.extract(fb, node, ab, ab2, ab3, result)

    ir match {
      case Ref(name, typ) =>
        assert(typ.isRealizable)
        ir
      case x@AggLet(name, value, body, _) =>
        ab3 += x
        extract(body)
      case x: ApplyAggOp =>
        val i = ab.length
        ab += IRAgg(i, newAggregator(fb, x), x.typ)
        ab2 += AggOps(
          x.initOpArgs.map(InitOp(i, _, x.aggSig)),
          SeqOp(i, x.seqOpArgs, x.aggSig))
        GetTupleElement(result, i)
      case AggFilter(cond, aggIR, _) =>
        val newBuilder = new ArrayBuilder[AggOps]()
        val aggLetAB = new ArrayBuilder[AggLet]()
        val transformed = this.extract(fb, aggIR, ab, newBuilder, aggLetAB, result)
        val (initOp, seqOp) = newBuilder.result().map { case AggOps(x, y) => (x, y) }.unzip
        val io = if (initOp.flatten.isEmpty) None else Some(Begin(initOp.flatten.toFastIndexedSeq))
        ab2 += AggOps(io,
          If(cond, ExtractAggregators.addLets(Begin(seqOp), aggLetAB.result()), Begin(FastIndexedSeq())))
        transformed
      case AggExplode(array, name, aggBody, _) =>
        val newBuilder = new ArrayBuilder[AggOps]()

        val aggLetAB = new ArrayBuilder[AggLet]()
        val transformed = this.extract(fb, aggBody, ab, newBuilder, aggLetAB, result)

        // collect lets that depend on `name`, push the rest up
        val (dependent, independent) = aggLetAB.result().partition(l => Mentions(l.value, name))
        ab3 ++= independent

        val (initOp, seqOp) = newBuilder.result().map { case AggOps(x, y) => (x, y) }.unzip
        val io = if (initOp.flatten.isEmpty) None else Some(Begin(initOp.flatten.toFastIndexedSeq))
        ab2 += AggOps(
          io,
          ArrayFor(array, name, ExtractAggregators.addLets(Begin(seqOp), dependent)))
        transformed
      case AggGroupBy(key, aggIR, _) =>

        val newRVAggBuilder = new ArrayBuilder[IRAgg]()
        val newBuilder = new ArrayBuilder[AggOps]()
        val newRef = Ref(genUID(), null)
        val transformed = this.extract(fb, aggIR, newRVAggBuilder, newBuilder, ab3, GetField(newRef, "value"))

        val nestedAggs = newRVAggBuilder.result()
        val agg = Code.newInstance[KeyedRegionValueAggregator, Array[RegionValueAggregator], Type](newArray(fb, nestedAggs.map(_.rvAgg)), fb.getType(key.typ))
        val aggSig = AggSignature(Group(), Seq(), Some(Seq(TVoid)), Seq(key.typ, TVoid))
        val rt = TDict(key.typ, TTuple(nestedAggs.map(_.rt): _*))
        newRef._typ = -rt.elementType

        val (initOp, seqOp) = newBuilder.result().map { case AggOps(x, y) => (x, y) }.unzip
        val i = ab.length
        ab += IRAgg(i, agg, rt)
        ab2 += AggOps(
          Some(InitOp(i, FastIndexedSeq(Begin(initOp.flatten.toFastIndexedSeq)), aggSig)),
          SeqOp(I32(i), FastIndexedSeq(key, Begin(seqOp)), aggSig))

        ToDict(ArrayMap(ToArray(GetTupleElement(result, i)), newRef.name, MakeTuple(FastSeq(GetField(newRef, "key"), transformed))))

      case AggArrayPerElement(a, elementName, indexName, aggBody, knownLength, _) =>
        val newRVAggBuilder = new ArrayBuilder[IRAgg]()
        val newBuilder = new ArrayBuilder[AggOps]()
        val newRef = Ref(genUID(), null)

        val aggLetAB = new ArrayBuilder[AggLet]()
        val transformed = this.extract(fb, aggBody, newRVAggBuilder, newBuilder, aggLetAB, newRef)

        // collect lets that depend on `elementName`, push the rest up
        val (dependent, independent) = aggLetAB.result().partition(l => Mentions(l.value, elementName))
        ab3 ++= independent

        val nestedAggs = newRVAggBuilder.result()
        val agg = Code.newInstance[ArrayElementsAggregator, Array[RegionValueAggregator]](newArray(fb, nestedAggs.map(_.rvAgg)))
        val rt = TArray(TTuple(nestedAggs.map(_.rt): _*))
        newRef._typ = -rt.elementType

        val (knownLengthSig, knownLengthIRSeq) = knownLength.map(l => (FastSeq(TInt32()), FastSeq(l)))
          .getOrElse((FastSeq(), FastSeq()))
        val aggSigCheck = AggSignature(AggElementsLengthCheck(), FastSeq(),
          Some(FastSeq(TVoid) ++ knownLengthSig),
          FastSeq(TInt32()))
        val aggSig = AggSignature(AggElements(), Seq(), None, Seq(TInt32(), TVoid))

        val aUID = genUID()
        val iUID = genUID()

        val (initOp, seqOp) = newBuilder.result().map { case AggOps(x, y) => (x, y) }.unzip
        val i = ab.length
        ab += IRAgg(i, agg, rt)
        ab2 += AggOps(
          Some(InitOp(i, FastIndexedSeq(Begin(initOp.flatten.toFastIndexedSeq)) ++ knownLengthIRSeq, aggSigCheck)),
          Let(
            aUID,
            a,
            Begin(FastIndexedSeq(
              SeqOp(I32(i), FastIndexedSeq(ArrayLen(Ref(aUID, a.typ))), aggSigCheck),
              ArrayFor(
                ArrayRange(I32(0), ArrayLen(Ref(aUID, a.typ)), I32(1)),
                iUID,
                Let(
                  elementName,
                  ArrayRef(Ref(aUID, a.typ), Ref(iUID, TInt32())),
                  ExtractAggregators.addLets(SeqOp(
                    I32(i),
                    FastIndexedSeq(Ref(iUID, TInt32()), Begin(seqOp.toFastIndexedSeq)),
                    aggSig), dependent)
                ))))))
        val rUID = genUID()
        Let(
          rUID,
          GetTupleElement(result, i),
          ArrayMap(
            ArrayRange(
              I32(0),
              ArrayLen(Ref(rUID, rt)),
              I32(1)),
            indexName,
            Let(
              newRef.name,
              ArrayRef(
                Ref(rUID, rt),
                Ref(indexName, TInt32())
              ),
              transformed
            )
          ))

      case x: ArrayAgg => x
      case x: ArrayAggScan => x
      case _ => MapIR(extract)(ir)
    }
  }

  private def newAggregator(fb: EmitFunctionBuilder[_], ir: ApplyAggOp): Code[RegionValueAggregator] = ir match {
    case x@ApplyAggOp(constructorArgs, _, _, aggSig) =>
      var codeConstructorArgs = constructorArgs.map(Emit.toCode(_, fb, 1))

      aggSig match {
        case AggSignature(Collect() | Take() | CollectAsSet(), _, _, Seq(t@(_: TBoolean | _: TInt32 | _: TInt64 | _: TFloat32 | _: TFloat64 | _: TCall))) =>
        case AggSignature(Collect() | Take() | CollectAsSet(), _, _, Seq(t)) =>
          codeConstructorArgs ++= FastIndexedSeq(EmitTriplet(Code._empty, const(false), fb.getType(t)))
        case AggSignature(Counter(), _, _, Seq(t@(_: TBoolean))) =>
        case AggSignature(Counter(), _, _, Seq(t)) =>
          codeConstructorArgs = FastIndexedSeq(EmitTriplet(Code._empty, const(false), fb.getType(t)))
        case AggSignature(TakeBy(), _, _, Seq(aggType, keyType)) =>
          codeConstructorArgs ++= FastIndexedSeq(EmitTriplet(Code._empty, const(false), fb.getType(aggType)),
            EmitTriplet(Code._empty, const(false), fb.getType(keyType)))
        case AggSignature(InfoScore(), _, _, Seq(t)) =>
          codeConstructorArgs = FastIndexedSeq(EmitTriplet(Code._empty, const(false), fb.getType(t)))
        case AggSignature(LinearRegression(), _, _, Seq(_, xType)) =>
          codeConstructorArgs ++= FastIndexedSeq(EmitTriplet(Code._empty, const(false), fb.getType(xType)))
        case AggSignature(Sum(), _, _, Seq(t@(_: TInt64 | _: TFloat64))) =>
        case AggSignature(Sum(), _, _, Seq(t)) =>
          codeConstructorArgs = FastIndexedSeq(EmitTriplet(Code._empty, const(false), fb.getType(t)))
        case _ =>
      }

      Code(
        Code(codeConstructorArgs.map(_.setup): _*),
        AggOp.get(aggSig).asInstanceOf[CodeAggregator[_]]
          .stagedNew(codeConstructorArgs.map(_.v).toArray, codeConstructorArgs.map(_.m).toArray))
        .asInstanceOf[Code[RegionValueAggregator]]
  }
}
