package is.hail.expr.ir

import is.hail.expr.types.virtual.{TArray, TInt32, TInt64, TStruct}
import is.hail.table.Ascending
import is.hail.utils._

object Simplify {

  /** Transform 'ir' using simplification rules until none apply.
    */
  def apply(ir: BaseIR): BaseIR = Simplify(ir, allowRepartitioning = true)

  /** Use 'allowRepartitioning'=false when in a context where simplification
    * should not change the partitioning of the result of 'ast', such as when
    * some parent (downstream) node of 'ast' uses seeded randomness.
    */
  private[ir] def apply(ast: BaseIR, allowRepartitioning: Boolean): BaseIR =
    ast match {
      case ir: IR => simplifyValue(ir)
      case tir: TableIR => simplifyTable(allowRepartitioning)(tir)
      case mir: MatrixIR => simplifyMatrix(allowRepartitioning)(mir)
      case bmir: BlockMatrixIR => simplifyBlockMatrix(bmir)
    }

  private[this] def visitNode[T <: BaseIR](
    visitChildren: BaseIR => BaseIR,
    transform: T => Option[T],
    post: => (T => T)
  )(t: T): T = {
    val t1 = t.mapChildren(visitChildren).asInstanceOf[T]
    transform(t1).map(post).getOrElse(t1)
  }

  private[this] def simplifyValue: IR => IR =
    visitNode(
      Simplify(_),
      rewriteValueNode,
      simplifyValue)

  private[this] def simplifyTable(allowRepartitioning: Boolean)(tir: TableIR): TableIR =
    visitNode(
      Simplify(_, allowRepartitioning && isDeterministicallyRepartitionable(tir)),
      rewriteTableNode(allowRepartitioning),
      simplifyTable(allowRepartitioning)
    )(tir)

  private[this] def simplifyMatrix(allowRepartitioning: Boolean)(mir: MatrixIR): MatrixIR =
    visitNode(
      Simplify(_, allowRepartitioning && isDeterministicallyRepartitionable(mir)),
      rewriteMatrixNode(allowRepartitioning),
      simplifyMatrix(allowRepartitioning)
    )(mir)

  private[this] def simplifyBlockMatrix(bmir: BlockMatrixIR): BlockMatrixIR = {
    visitNode(
      Simplify(_),
      rewriteBlockMatrixNode,
      simplifyBlockMatrix
    )(bmir)
  }

  private[this] def rewriteValueNode: IR => Option[IR] = valueRules.lift

  private[this] def rewriteTableNode(allowRepartitioning: Boolean)(tir: TableIR): Option[TableIR] =
    tableRules(allowRepartitioning && isDeterministicallyRepartitionable(tir)).lift(tir)

  private[this] def rewriteMatrixNode(allowRepartitioning: Boolean)(mir: MatrixIR): Option[MatrixIR] =
    matrixRules(allowRepartitioning && isDeterministicallyRepartitionable(mir)).lift(mir)

  private[this] def rewriteBlockMatrixNode: BlockMatrixIR => Option[BlockMatrixIR] = blockMatrixRules.lift

  /** Returns true if 'x' propagates missingness, meaning if any child of 'x'
    * evaluates to missing, then 'x' will evaluate to missing.
    */
  private[this] def isStrict(x: IR): Boolean = {
    x match {
      case _: Apply |
           _: ApplyUnaryPrimOp |
           _: ApplyBinaryPrimOp |
           _: ArrayRange |
           _: ArrayRef |
           _: ArrayLen |
           _: GetField |
           _: GetTupleElement => true
      case ApplyComparisonOp(op, _, _) => op.strict
      case f: ApplySeeded => f.implementation.isStrict
      case _ => false
    }
  }

  /** Returns true if 'x' will never evaluate to missing.
    */
  private[this] def isDefinitelyDefined(x: IR): Boolean = {
    x match {
      case _: MakeArray |
           _: MakeStruct |
           _: MakeTuple |
           _: IsNA |
           ApplyComparisonOp(EQWithNA(_, _), _, _) |
           ApplyComparisonOp(NEQWithNA(_, _), _, _) |
           _: I32 | _: I64 | _: F32 | _: F64 | True() | False() => true
      case _ => false
    }
  }

  /** Returns true if changing the partitioning of child (upstream) nodes of
    * 'ir' can not otherwise change the contents of the result of 'ir'.
    */
  private[this] def isDeterministicallyRepartitionable(ir: BaseIR): Boolean =
    ir.children.forall {
      case child: IR => !Exists(child, _.isInstanceOf[ApplySeeded])
      case _ => true
    }

  private[this] def areFieldSelects(fields: Seq[(String, IR)]): Boolean = {
    assert(fields.nonEmpty)
    fields.head match {
      case (_, GetField(s1, _)) =>
        fields.forall {
          case (f1, GetField(s2, f2)) if f1 == f2 && s1 == s2 => true
          case _ => false
        }
      case _ => false
    }
  }

  private[this] def valueRules: PartialFunction[IR, IR] = {
    // propagate NA
    case x: IR if isStrict(x) && Children(x).exists(_.isInstanceOf[NA]) =>
      NA(x.typ)

    case x@If(NA(_), _, _) => NA(x.typ)

    case x@ArrayMap(NA(_), _, _) => NA(x.typ)

    case x@ArrayFlatMap(NA(_), _, _) => NA(x.typ)

    case x@ArrayFilter(NA(_), _, _) => NA(x.typ)

    case x@ArrayFold(NA(_), _, _, _, _) => NA(x.typ)

    case IsNA(NA(_)) => True()

    case IsNA(x) if isDefinitelyDefined(x) => False()

    case x@If(True(), cnsq, _) if x.typ == cnsq.typ => cnsq

    case x@If(False(), _, altr) if x.typ == altr.typ => altr

    case If(c, cnsq, altr) if cnsq == altr =>
      if (cnsq.typ.required)
        cnsq
      else
        If(IsNA(c), NA(cnsq.typ), cnsq)

    case Cast(x, t) if x.typ == t => x

    case ApplyBinaryPrimOp(Add(), I32(0), x) => x
    case ApplyBinaryPrimOp(Add(), x, I32(0)) => x
    case ApplyBinaryPrimOp(Subtract(), I32(0), x) => x
    case ApplyBinaryPrimOp(Subtract(), x, I32(0)) => x

    case ApplyIR("indexArray", Seq(a, i@I32(v)), _) if v >= 0 =>
      ArrayRef(a, i)

    case ArrayLen(MakeArray(args, _)) => I32(args.length)

    case ArrayLen(ArrayRange(start, end, I32(1))) => ApplyBinaryPrimOp(Subtract(), end, start)

    case ArrayLen(ArrayMap(a, _, _)) => ArrayLen(a)

    case ArrayLen(ArrayFlatMap(a, _, MakeArray(args, _))) => ApplyBinaryPrimOp(Multiply(), I32(args.length), ArrayLen(a))

    case ArrayLen(ArraySort(a, _, _, _)) => ArrayLen(a)

    case ArrayRef(MakeArray(args, _), I32(i)) if i >= 0 && i < args.length => args(i)

    case ArrayFilter(a, _, True()) => a

    case ArrayFor(_, _, Begin(Seq())) => Begin(FastIndexedSeq())

    case ArrayFold(ArrayMap(a, n1, b), zero, accumName, valueName, body) => ArrayFold(a, zero, accumName, n1, Let(valueName, b, body))

    case ArrayFlatMap(ArrayMap(a, n1, b1), n2, b2) =>
      ArrayFlatMap(a, n1, Let(n2, b1, b2))

    case ArrayMap(ArrayMap(a, n1, b1), n2, b2) =>
      ArrayMap(a, n1, Let(n2, b1, b2))

    case GetField(MakeStruct(fields), name) =>
      val (_, x) = fields.find { case (n, _) => n == name }.get
      x

    case GetField(InsertFields(old, fields, _), name) =>
      fields.find { case (n, _) => n == name } match {
        case Some((_, x)) => x
        case None => GetField(old, name)
      }

    case GetField(SelectFields(old, fields), name) => GetField(old, name)

    case InsertFields(InsertFields(base, fields1, fieldOrder1), fields2, fieldOrder2) =>
        val fields2Set = fields2.map(_._1).toSet
        val newFields = fields1.filter { case (name, _) => !fields2Set.contains(name) } ++ fields2
      (fieldOrder1, fieldOrder2) match {
        case (Some(fo1), None) =>
          val fields1Set = fo1.toSet
          val fieldOrder = fo1 ++ fields2.map(_._1).filter(!fields1Set.contains(_))
          InsertFields(base, newFields, Some(fieldOrder))
        case _ =>
          InsertFields(base, newFields, fieldOrder2)
      }

    case InsertFields(MakeStruct(fields1), fields2, fieldOrder) =>
      val fields1Map = fields1.toMap
      val fields2Map = fields2.toMap

      fieldOrder match {
        case Some(fo) =>
          MakeStruct(fo.map(f => f -> fields2Map.getOrElse(f, fields1Map(f))))
        case None =>
          val finalFields = fields1.map { case (name, fieldIR) => name -> fields2Map.getOrElse(name, fieldIR) } ++
            fields2.filter { case (name, _) => !fields1Map.contains(name) }
          MakeStruct(finalFields)
      }


    case InsertFields(struct, Seq(), _) => struct

    case SelectFields(old, fields) if coerce[TStruct](old.typ).fieldNames sameElements fields =>
      old

    case SelectFields(SelectFields(old, _), fields) =>
      SelectFields(old, fields)

    case SelectFields(MakeStruct(fields), fieldNames) =>
      val makeStructFields = fields.toMap
      MakeStruct(fieldNames.map(f => f -> makeStructFields(f)))

    case x@SelectFields(InsertFields(struct, insertFields, _), selectFields) =>
      val selectSet = selectFields.toSet
      val insertFields2 = insertFields.filter { case (fName, _) => selectSet.contains(fName) }
      val structSet = struct.typ.asInstanceOf[TStruct].fieldNames.toSet
      val selectFields2 = selectFields.filter(structSet.contains)
      val x2 = InsertFields(SelectFields(struct, selectFields2), insertFields2, Some(selectFields.toFastIndexedSeq))
      assert(x2.typ == x.typ)
      x2

    case GetTupleElement(MakeTuple(xs), idx) => xs(idx)

    case TableCount(MatrixColsTable(child)) if child.columnCount.isDefined => I64(child.columnCount.get)

    case TableCount(child) if child.partitionCounts.isDefined => I64(child.partitionCounts.get.sum)
    case TableCount(TableMapGlobals(child, _)) => TableCount(child)
    case TableCount(TableMapRows(child, _)) => TableCount(child)
    case TableCount(TableRepartition(child, _, _)) => TableCount(child)
    case TableCount(TableUnion(children)) =>
      children.map(TableCount).reduce[IR](ApplyBinaryPrimOp(Add(), _, _))
    case TableCount(TableKeyBy(child, _, _)) => TableCount(child)
    case TableCount(TableOrderBy(child, _)) => TableCount(child)
    case TableCount(TableLeftJoinRightDistinct(child, _, _)) => TableCount(child)
    case TableCount(TableIntervalJoin(child, _, _)) => TableCount(child)
    case TableCount(TableRange(n, _)) => I64(n)
    case TableCount(TableParallelize(rowsAndGlobal, _)) => Cast(ArrayLen(GetField(rowsAndGlobal, "rows")), TInt64())
    case TableCount(TableRename(child, _, _)) => TableCount(child)
    case TableCount(TableAggregateByKey(child, _)) => TableCount(TableDistinct(child))

    // TableGetGlobals should simplify very aggressively
    case TableGetGlobals(child) if child.typ.globalType == TStruct() => MakeStruct(FastSeq())
    case TableGetGlobals(TableKeyBy(child, _, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableFilter(child, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableHead(child, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableRepartition(child, _, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableJoin(child1, child2, _, _)) =>
      val g1 = TableGetGlobals(child1)
      val g2 = TableGetGlobals(child2)
      val g1s = genUID()
      val g2s = genUID()
      Let(g1s, g1,
        Let(g2s, g2,
          MakeStruct(
            g1.typ.asInstanceOf[TStruct].fields.map(f => f.name -> (GetField(Ref(g1s, g1.typ), f.name): IR)) ++
              g2.typ.asInstanceOf[TStruct].fields.map(f => f.name -> (GetField(Ref(g2s, g2.typ), f.name): IR)))))
    case TableGetGlobals(x@TableMultiWayZipJoin(children, _, globalName)) =>
      MakeStruct(FastSeq(globalName -> MakeArray(children.map(TableGetGlobals), TArray(children.head.typ.globalType))))
    case TableGetGlobals(TableZipUnchecked(left, _)) => TableGetGlobals(left)
    case TableGetGlobals(TableLeftJoinRightDistinct(child, _, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableMapRows(child, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableMapGlobals(child, newGlobals)) =>
      val uid = genUID()
      val ref = Ref(uid, child.typ.globalType)
      Let(uid, TableGetGlobals(child), Subst(newGlobals, Env.empty[IR].bind("global", ref)))
    case TableGetGlobals(TableExplode(child, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableUnion(children)) => TableGetGlobals(children.head)
    case TableGetGlobals(TableDistinct(child)) => TableGetGlobals(child)
    case TableGetGlobals(TableAggregateByKey(child, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableKeyByAndAggregate(child, _, _, _, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableOrderBy(child, _)) => TableGetGlobals(child)
    case TableGetGlobals(TableRename(child, _, globalMap)) =>
      if (globalMap.isEmpty)
        TableGetGlobals(child)
      else {
        val uid = genUID()
        val ref = Ref(uid, child.typ.globalType)
        Let(uid, TableGetGlobals(child), MakeStruct(child.typ.globalType.fieldNames.map { f =>
          globalMap.getOrElse(f, f) -> GetField(ref, f)
        }))
      }

    case TableCollect(TableParallelize(x, _)) => x
    case ArrayLen(GetField(TableCollect(child), "rows")) => TableCount(child)

    case ApplyIR("annotate", Seq(s, MakeStruct(fields)), _) =>
      InsertFields(s, fields)

    // simplify Boolean equality
    case ApplyComparisonOp(EQ(_, _), expr, True()) => expr
    case ApplyComparisonOp(EQ(_, _), True(), expr) => expr
    case ApplyComparisonOp(EQ(_, _), expr, False()) => ApplyUnaryPrimOp(Bang(), expr)
    case ApplyComparisonOp(EQ(_, _), False(), expr) => ApplyUnaryPrimOp(Bang(), expr)
  }

  private[this] def tableRules(canRepartition: Boolean): PartialFunction[TableIR, TableIR] = {

    case TableRename(child, m1, m2) if m1.isTrivial && m2.isTrivial => child

    // TODO: Write more rules like this to bubble 'TableRename' nodes towards the root.
    case t@TableRename(TableKeyBy(child, keys, isSorted), rowMap, globalMap) =>
      TableKeyBy(TableRename(child, rowMap, globalMap), keys.map(t.rowF), isSorted)

    case TableFilter(t, True()) => t

    case TableFilter(TableRead(typ, _, tr), False() | NA(_)) =>
      TableRead(typ, dropRows = true, tr)

    case TableFilter(TableFilter(t, p1), p2) =>
      TableFilter(t,
        ApplySpecial("&&", Array(p1, p2)))

    case TableFilter(TableKeyBy(child, key, isSorted), p) if canRepartition => TableKeyBy(TableFilter(child, p), key, isSorted)
    case TableFilter(TableRepartition(child, n, strategy), p) => TableRepartition(TableFilter(child, p), n, strategy)

    case TableOrderBy(TableKeyBy(child, _, _), sortFields) => TableOrderBy(child, sortFields)

    case TableFilter(TableOrderBy(child, sortFields), pred) if canRepartition =>
      TableOrderBy(TableFilter(child, pred), sortFields)

    case TableKeyBy(TableOrderBy(child, sortFields), keys, false) if canRepartition =>
      TableKeyBy(child, keys, false)

    case TableKeyBy(TableKeyBy(child, _, _), keys, false) if canRepartition =>
      TableKeyBy(child, keys, false)

    case TableKeyBy(TableKeyBy(child, _, true), keys, true) if canRepartition =>
      TableKeyBy(child, keys, true)

    case TableKeyBy(child, key, _) if key == child.typ.key => child

    case TableMapRows(child, Ref("row", _)) => child

    case TableMapRows(child, MakeStruct(fields))
      if fields.length == child.typ.rowType.size
        && fields.zip(child.typ.rowType.fields).forall { case ((_, ir), field) =>
        ir == GetField(Ref("row", field.typ), field.name)
      } =>
      val renamedPairs = for {
        (oldName, (newName, _)) <- child.typ.rowType.fieldNames zip fields
        if oldName != newName
      } yield oldName -> newName
      TableRename(child, Map(renamedPairs: _*), Map.empty)

    case TableMapRows(TableMapRows(child, newRow1), newRow2) if !ContainsScan(newRow2) =>
      TableMapRows(child, Let("row", newRow1, newRow2))

    case TableMapGlobals(child, Ref("global", _)) => child

    // flatten unions
    case TableUnion(children) if children.exists(_.isInstanceOf[TableUnion]) & canRepartition =>
      TableUnion(children.flatMap {
        case u: TableUnion => u.children
        case c => Some(c)
      })

    case MatrixRowsTable(MatrixUnionRows(children)) =>
      TableUnion(children.map(MatrixRowsTable))

    case MatrixColsTable(MatrixUnionRows(children)) =>
      MatrixColsTable(children(0))

    // Ignore column or row data that is immediately dropped
    case MatrixRowsTable(MatrixRead(typ, false, dropRows, reader)) =>
      MatrixRowsTable(MatrixRead(typ, dropCols = true, dropRows, reader))

    case MatrixColsTable(MatrixRead(typ, dropCols, false, reader)) =>
      MatrixColsTable(MatrixRead(typ, dropCols, dropRows = true, reader))

    case MatrixRowsTable(MatrixFilterRows(child, pred))
      if !Mentions(pred, "g") && !Mentions(pred, "sa") && !ContainsAgg(pred) =>
      val mrt = MatrixRowsTable(child)
      TableFilter(
        mrt,
        Subst(pred, Env("va" -> Ref("row", mrt.typ.rowType))))

    case MatrixRowsTable(MatrixMapGlobals(child, newGlobals)) => TableMapGlobals(MatrixRowsTable(child), newGlobals)
    case MatrixRowsTable(MatrixMapCols(child, _, _)) => MatrixRowsTable(child)
    case MatrixRowsTable(MatrixMapEntries(child, _)) => MatrixRowsTable(child)
    case MatrixRowsTable(MatrixFilterEntries(child, _)) => MatrixRowsTable(child)
    case MatrixRowsTable(MatrixFilterCols(child, _)) => MatrixRowsTable(child)
    case MatrixRowsTable(MatrixAggregateColsByKey(child, _, _)) => MatrixRowsTable(child)
    case MatrixRowsTable(MatrixChooseCols(child, _)) => MatrixRowsTable(child)
    case MatrixRowsTable(MatrixCollectColsByKey(child)) => MatrixRowsTable(child)
    case MatrixRowsTable(MatrixKeyRowsBy(child, keys, isSorted)) => TableKeyBy(MatrixRowsTable(child), keys, isSorted)

    case MatrixColsTable(x@MatrixMapCols(child, newRow, newKey))
      if newKey.isEmpty && !Mentions(newRow, "g") && !Mentions(newRow, "va") &&
        !ContainsAgg(newRow) && canRepartition && isDeterministicallyRepartitionable(x) && !ContainsScan(newRow) =>
      val mct = MatrixColsTable(child)
      TableMapRows(
        mct,
        Subst(newRow, Env("sa" -> Ref("row", mct.typ.rowType))))

    case MatrixColsTable(MatrixFilterCols(child, pred))
      if !Mentions(pred, "g") && !Mentions(pred, "va") =>
      val mct = MatrixColsTable(child)
      TableFilter(
        mct,
        Subst(pred, Env("sa" -> Ref("row", mct.typ.rowType))))

    case MatrixColsTable(MatrixMapGlobals(child, newGlobals)) => TableMapGlobals(MatrixColsTable(child), newGlobals)
    case MatrixColsTable(MatrixMapRows(child, _)) => MatrixColsTable(child)
    case MatrixColsTable(MatrixMapEntries(child, _)) => MatrixColsTable(child)
    case MatrixColsTable(MatrixFilterEntries(child, _)) => MatrixColsTable(child)
    case MatrixColsTable(MatrixFilterRows(child, _)) => MatrixColsTable(child)
    case MatrixColsTable(MatrixAggregateRowsByKey(child, _, _)) => MatrixColsTable(child)
    case MatrixColsTable(MatrixKeyRowsBy(child, _, _)) => MatrixColsTable(child)

    case TableMapGlobals(TableMapGlobals(child, ng1), ng2) => TableMapGlobals(child, Let("global", ng1, ng2))

    case TableHead(TableMapRows(child, newRow), n) =>
      TableMapRows(TableHead(child, n), newRow)

    case TableHead(TableRepartition(child, nPar, shuffle), n) if canRepartition =>
      TableRepartition(TableHead(child, n), nPar, shuffle)

    case TableHead(tr@TableRange(nRows, nPar), n) if canRepartition =>
      if (n < nRows)
        TableRange(n.toInt, (nPar.toFloat * n / nRows).toInt.max(1))
      else
        tr

    case TableHead(TableMapGlobals(child, newGlobals), n) =>
      TableMapGlobals(TableHead(child, n), newGlobals)

    case TableHead(TableOrderBy(child, sortFields), n)
      if !TableOrderBy.isAlreadyOrdered(sortFields, child.rvdType.key)
        && n < 256 && canRepartition =>
      // n < 256 is arbitrary for memory concerns
      val uid = genUID()
      val row = Ref("row", child.typ.rowType)
      val keyStruct = MakeStruct(sortFields.map(f => f.field -> GetField(row, f.field)))
      val aggSig = AggSignature(TakeBy(), FastSeq(TInt32()), None, FastSeq(row.typ, keyStruct.typ))
      val te =
        TableExplode(
          TableKeyByAndAggregate(child,
            MakeStruct(Seq(
              "row" -> ApplyAggOp(
                FastIndexedSeq(I32(n.toInt)),
                None,
                Array(row, keyStruct),
                aggSig))),
            MakeStruct(Seq()), // aggregate to one row
            Some(1), 10),
          FastIndexedSeq("row"))
      TableMapRows(te, GetField(Ref("row", te.typ.rowType), "row"))

    case TableKeyByAndAggregate(child, MakeStruct(Seq()), k@MakeStruct(keyFields), _, _) if canRepartition =>
      TableDistinct(TableKeyBy(TableMapRows(TableKeyBy(child, FastIndexedSeq()), k), k.typ.asInstanceOf[TStruct].fieldNames))

    case TableKeyByAndAggregate(child, expr, newKey, _, _)
      if newKey == MakeStruct(child.typ.key.map(k => k -> GetField(Ref("row", child.typ.rowType), k)))
        && child.typ.key.nonEmpty && canRepartition =>
      TableAggregateByKey(child, expr)

    case TableAggregateByKey(TableKeyBy(child, keys, _), expr) if canRepartition =>
      TableKeyByAndAggregate(child, expr, MakeStruct(keys.map(k => k -> GetField(Ref("row", child.typ.rowType), k))))

    case TableParallelize(TableCollect(child), _) if isDeterministicallyRepartitionable(child) => child
  }

  private[this] def matrixRules(canRepartition: Boolean): PartialFunction[MatrixIR, MatrixIR] = {
    case MatrixMapRows(child, Ref("va", _)) => child

    case MatrixKeyRowsBy(MatrixKeyRowsBy(child, _, _), keys, false) if canRepartition =>
      MatrixKeyRowsBy(child, keys, false)

    case MatrixKeyRowsBy(MatrixKeyRowsBy(child, _, true), keys, true) if canRepartition =>
      MatrixKeyRowsBy(child, keys, true)

    case MatrixMapCols(child, Ref("sa", _), None) => child

    case x@MatrixMapEntries(child, Ref("g", _)) =>
      assert(child.typ == x.typ)
      child


    case MatrixMapGlobals(child, Ref("global", _)) => child

    // flatten unions
    case MatrixUnionRows(children) if children.exists(_.isInstanceOf[MatrixUnionRows]) & canRepartition =>
      MatrixUnionRows(children.flatMap {
        case u: MatrixUnionRows => u.children
        case c => Some(c)
      })

    // Equivalent rewrites for the new Filter{Cols,Rows}IR
    case MatrixFilterRows(MatrixRead(typ, dropCols, _, reader), False() | NA(_)) =>
      MatrixRead(typ, dropCols, dropRows = true, reader)

    case MatrixFilterCols(MatrixRead(typ, _, dropRows, reader), False() | NA(_)) =>
      MatrixRead(typ, dropCols = true, dropRows, reader)

    // Keep all rows/cols = do nothing
    case MatrixFilterRows(m, True()) => m

    case MatrixFilterCols(m, True()) => m

    case MatrixFilterRows(MatrixFilterRows(child, pred1), pred2) => MatrixFilterRows(child, ApplySpecial("&&", FastSeq(pred1, pred2)))

    case MatrixFilterCols(MatrixFilterCols(child, pred1), pred2) => MatrixFilterCols(child, ApplySpecial("&&", FastSeq(pred1, pred2)))

    case MatrixFilterEntries(MatrixFilterEntries(child, pred1), pred2) => MatrixFilterEntries(child, ApplySpecial("&&", FastSeq(pred1, pred2)))

    case MatrixMapGlobals(MatrixMapGlobals(child, ng1), ng2) => MatrixMapGlobals(child, Let("global", ng1, ng2))
  }

  private[this] def blockMatrixRules: PartialFunction[BlockMatrixIR, BlockMatrixIR] = {
    case BlockMatrixBroadcast(child, IndexedSeq(0, 1), _, _, _) => child
  }
}
