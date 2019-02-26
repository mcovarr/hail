package is.hail.expr.ir

import is.hail.utils._

object Optimize {
  private def optimize(ir0: BaseIR, noisy: Boolean, canGenerateLiterals: Boolean, context: Option[String]): BaseIR = {
    val contextStr = context.map(s => s" ($s)").getOrElse("")
    if (noisy)
      log.info(s"optimize$contextStr: before: IR size ${ IRSize(ir0) }: \n" + Pretty(ir0, elideLiterals = true))

    var ir = ir0
    ir = FoldConstants(ir, canGenerateLiterals = canGenerateLiterals)
    ir = Simplify(ir)
    ir = ForwardLets(ir)
    ir = PruneDeadFields(ir)
    ir = Simplify(ir)

    if (ir.typ != ir0.typ)
      fatal(s"optimization changed type!\n  before: ${ ir0.typ }\n  after:  ${ ir.typ }" +
        s"\n  Before IR:\n  ----------\n${ Pretty(ir0) }\n  After IR:\n  ---------\n${ Pretty(ir) }")

    if (noisy)
      log.info(s"optimize$contextStr: after: IR size ${ IRSize(ir0) }:\n" + Pretty(ir, elideLiterals = true))

    ir
  }

  def apply(ir: TableIR, noisy: Boolean, canGenerateLiterals: Boolean): TableIR =
    optimize(ir, noisy, canGenerateLiterals, None).asInstanceOf[TableIR]

  def apply(ir: TableIR): TableIR = apply(ir, true, true)

  def apply(ir: MatrixIR, noisy: Boolean, canGenerateLiterals: Boolean): MatrixIR =
   optimize(ir, noisy, canGenerateLiterals, None).asInstanceOf[MatrixIR]

  def apply(ir: MatrixIR): MatrixIR = apply(ir, true, true)

  def apply(ir: BlockMatrixIR): BlockMatrixIR = ir //Currently no BlockMatrixIR that can be optimized

  def apply(ir: IR, noisy: Boolean, canGenerateLiterals: Boolean, context: Option[String]): IR =
    optimize(ir, noisy, canGenerateLiterals, context).asInstanceOf[IR]

  def apply(ir: IR): IR = apply(ir, true, true, None)
}
