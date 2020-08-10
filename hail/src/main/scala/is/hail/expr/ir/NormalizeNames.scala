package is.hail.expr.ir

import is.hail.utils._

class NormalizeNames(normFunction: Int => String, allowFreeVariables: Boolean = false) {
  var count: Int = 0

  def gen(): String = {
    count += 1
    normFunction(count)
  }

  def apply(ir: IR, env: Env[String]): IR = apply(ir, BindingEnv(env))

  def apply(ir: IR, env: BindingEnv[String]): IR = normalizeIR(ir, env).asInstanceOf[IR]

  def apply(ir: BaseIR): BaseIR = normalizeIR(ir, BindingEnv(agg=Some(Env.empty), scan=Some(Env.empty)))

  private def normalizeIR(ir: BaseIR, env: BindingEnv[String], context: Array[String] = Array()): BaseIR = {

    def normalizeBaseIR(next: BaseIR, env: BindingEnv[String] = env): BaseIR = normalizeIR(next, env, context :+ ir.getClass().getName())

    def normalize(next: IR, env: BindingEnv[String] = env): IR = normalizeIR(next, env, context :+ ir.getClass().getName()).asInstanceOf[IR]

    ir match {
      case Let(name, value, body) =>
        val newName = gen()
        Let(newName, normalize(value), normalize(body, env.copy(eval = env.eval.bind(name, newName))))
      case Ref(name, typ) =>
        val newName = env.eval.lookupOption(name) match {
          case Some(n) => n
          case None =>
            if (!allowFreeVariables)
              throw new RuntimeException(s"found free variable in normalize: $name, ${context.reverse.mkString(", ")}; ${env.pretty(x => x)}")
            else
              name
        }
        Ref(newName, typ)
      case Recur(name, args, typ) =>
        val newName = env.eval.lookupOption(name) match {
          case Some(n) => n
          case None =>
            if (!allowFreeVariables)
              throw new RuntimeException(s"found free loop variable in normalize: $name, ${context.reverse.mkString(", ")}; ${env.pretty(x => x)}")
            else
              name
        }
        Recur(newName, args.map(v => normalize(v)), typ)
      case AggLet(name, value, body, isScan) =>
        val newName = gen()
        val (valueEnv, bodyEnv) = if (isScan)
          env.promoteScan -> env.bindScan(name, newName)
        else
          env.promoteAgg -> env.bindAgg(name, newName)
        AggLet(newName, normalize(value, valueEnv), normalize(body, bodyEnv), isScan)
      case TailLoop(name, args, body) =>
        val newFName = gen()
        val newNames = Array.tabulate(args.length)(i => gen())
        val (names, values) = args.unzip
        TailLoop(newFName, newNames.zip(values.map(v => normalize(v))), normalize(body, env.copy(eval = env.eval.bind(names.zip(newNames) :+ name -> newFName: _*))))
      case ArraySort(a, left, right, lessThan) =>
        val newLeft = gen()
        val newRight = gen()
        ArraySort(normalize(a), newLeft, newRight, normalize(lessThan, env.bindEval(left -> newLeft, right -> newRight)))
      case StreamMap(a, name, body) =>
        val newName = gen()
        StreamMap(normalize(a), newName, normalize(body, env.bindEval(name, newName)))
      case StreamZip(as, names, body, behavior) =>
        val newNames = names.map(_ => gen())
        StreamZip(as.map(normalize(_)), newNames, normalize(body, env.bindEval(names.zip(newNames): _*)), behavior)
      case StreamZipJoin(as, key, curKey, curVals, joinF) =>
        val newCurKey = gen()
        val newCurVals = gen()
        StreamZipJoin(as.map(normalize(_)), key, newCurKey, newCurVals, normalize(joinF, env.bindEval(curKey -> newCurKey, curVals -> newCurVals)))
      case StreamFilter(a, name, body) =>
        val newName = gen()
        StreamFilter(normalize(a), newName, normalize(body, env.bindEval(name, newName)))
      case StreamFlatMap(a, name, body) =>
        val newName = gen()
        StreamFlatMap(normalize(a), newName, normalize(body, env.bindEval(name, newName)))
      case StreamFold(a, zero, accumName, valueName, body) =>
        val newAccumName = gen()
        val newValueName = gen()
        StreamFold(normalize(a), normalize(zero), newAccumName, newValueName, normalize(body, env.bindEval(accumName -> newAccumName, valueName -> newValueName)))
      case StreamFold2(a, accum, valueName, seq, res) =>
        val newValueName = gen()
        val (accNames, newAcc) = accum.map { case (old, ir) =>
          val newName = gen()
          ((old, newName), (newName, normalize(ir)))
        }.unzip
        val resEnv = env.bindEval(accNames: _*)
        val seqEnv = resEnv.bindEval(valueName, newValueName)
        StreamFold2(normalize(a), newAcc, newValueName, seq.map(normalize(_, seqEnv)), normalize(res, resEnv))
      case StreamScan(a, zero, accumName, valueName, body) =>
        val newAccumName = gen()
        val newValueName = gen()
        StreamScan(normalize(a), normalize(zero), newAccumName, newValueName, normalize(body, env.bindEval(accumName -> newAccumName, valueName -> newValueName)))
      case StreamFor(a, valueName, body) =>
        val newValueName = gen()
        StreamFor(normalize(a), newValueName, normalize(body, env.bindEval(valueName, newValueName)))
      case StreamAgg(a, name, body) =>
        // FIXME: Uncomment when bindings are threaded through test suites
        // assert(env.agg.isEmpty)
        val newName = gen()
        StreamAgg(normalize(a), newName, normalize(body, env.copy(agg = Some(env.eval.bind(name, newName)))))
      case RunAggScan(a, name, init, seq, result, sig) =>
        val newName = gen()
        val e2 = env.bindEval(name, newName)
        RunAggScan(normalize(a), newName, normalize(init, env), normalize(seq, e2), normalize(result, e2), sig)
      case StreamAggScan(a, name, body) =>
        // FIXME: Uncomment when bindings are threaded through test suites
        // assert(env.scan.isEmpty)
        val newName = gen()
        val newEnv = env.eval.bind(name, newName)
        StreamAggScan(normalize(a), newName, normalize(body, env.copy(eval = newEnv, scan = Some(newEnv))))
      case StreamJoinRightDistinct(left, right, lKey, rKey, l, r, joinF, joinType) =>
        val newL = gen()
        val newR = gen()
        val newEnv = env.bindEval(l -> newL, r -> newR)
        StreamJoinRightDistinct(normalize(left), normalize(right), lKey, rKey, newL, newR, normalize(joinF, newEnv), joinType)
      case NDArrayMap(nd, name, body) =>
        val newName = gen()
        NDArrayMap(normalize(nd), newName, normalize(body, env.bindEval(name -> newName)))
      case NDArrayMap2(l, r, lName, rName, body) =>
        val newLName = gen()
        val newRName = gen()
        NDArrayMap2(normalize(l), normalize(r), newLName, newRName, normalize(body, env.bindEval(lName -> newLName, rName -> newRName)))
      case AggArrayPerElement(a, elementName, indexName, aggBody, knownLength, isScan) =>
        val newElementName = gen()
        val newIndexName = gen()
        val (aEnv, bodyEnv) = if (isScan)
          env.promoteScan -> env.bindScan(elementName, newElementName)
        else
          env.promoteAgg -> env.bindAgg(elementName, newElementName)
        AggArrayPerElement(normalize(a, aEnv), newElementName, newIndexName, normalize(aggBody, bodyEnv.bindEval(indexName, newIndexName)), knownLength.map(normalize(_, env)), isScan)
      case CollectDistributedArray(ctxs, globals, cname, gname, body) =>
        val newC = gen()
        val newG = gen()
        CollectDistributedArray(normalize(ctxs), normalize(globals), newC, newG, normalize(body, BindingEnv.eval(cname -> newC, gname -> newG)))
      case RelationalLet(name, value, body) =>
        RelationalLet(name, normalize(value, BindingEnv.empty), normalize(body))
      case ShuffleWith(keyFields, rowType, rowEType, keyEType, name, writer, readers) =>
        val newName = gen()
        ShuffleWith(keyFields, rowType, rowEType, keyEType, newName,
          normalize(writer, env.copy(eval = env.eval.bind(name, newName))),
          normalize(readers, env.copy(eval = env.eval.bind(name, newName))))
      case x => x.copy(x.children.iterator.zipWithIndex.map { case (child, i) =>
        normalizeBaseIR(child, ChildBindings.transformed(x, i, env, { case (name, _) => name }))
      }.toFastIndexedSeq)

    }
  }
}
