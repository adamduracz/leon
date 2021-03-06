/* Copyright 2009-2014 EPFL, Lausanne */

package leon
package synthesis

import purescala.Common._
import purescala.Definitions.{Program, FunDef, ModuleDef, DefType}
import purescala.TreeOps._
import purescala.Trees._
import purescala.ScalaPrinter

import solvers._
import solvers.combinators._
import solvers.z3._

import java.io.File

import synthesis.graph._

class Synthesizer(val context : LeonContext,
                  val functionContext: FunDef,
                  val program: Program,
                  val problem: Problem,
                  val options: SynthesisOptions) {

  val reporter = context.reporter

  def getSearch(): Search = {
    if (options.manualSearch) {
      new ManualSearch(context, problem, options.costModel)
    } else if (options.searchWorkers > 1) {
      ???
      //new ParallelSearch(this, problem, options.searchWorkers)
    } else {
      new SimpleSearch(context, problem, options.costModel, options.searchBound)
    }
  }

  def synthesize(): (Search, Stream[Solution]) = {
    val s = getSearch();

    val t = context.timers.synthesis.search.start()

    val sctx = SynthesisContext.fromSynthesizer(this)
    val sols = s.search(sctx)

    val diff = t.stop()
    reporter.info("Finished in "+diff+"ms")

    (s, sols)
  }

  def validate(results: (Search, Stream[Solution])): (Search, Stream[(Solution, Boolean)]) = {
    val (s, sols) = results

    val result = sols.map {
      case sol if sol.isTrusted =>
        (sol, true)
      case sol =>
        validateSolution(s, sol, 5000L)
    }

    (s, if (result.isEmpty) {
      List((new PartialSolution(s.g, true).getSolution, false)).toStream
    } else {
      result
    })
  }

  def validateSolution(search: Search, sol: Solution, timeoutMs: Long): (Solution, Boolean) = {
    import verification.AnalysisPhase._
    import verification.VerificationContext

    val ssol = sol.toSimplifiedExpr(context, program)
    reporter.info("Solution requires validation")

    val (npr, fds) = solutionToProgram(sol)

    val solverf = SolverFactory(() => (new FairZ3Solver(context, npr) with TimeoutSolver).setTimeout(timeoutMs))

    val vctx = VerificationContext(context, npr, solverf, context.reporter)
    val vcs = generateVerificationConditions(vctx, Some(fds.map(_.id.name).toSeq))
    val vcreport = checkVerificationConditions(vctx, vcs)

    if (vcreport.totalValid == vcreport.totalConditions) {
      (sol, true)
    } else if (vcreport.totalValid + vcreport.totalUnknown == vcreport.totalConditions) {
      reporter.warning("Solution may be invalid:")
      (sol, false)
    } else {
      reporter.warning("Solution was invalid:")
      reporter.warning(fds.map(ScalaPrinter(_)).mkString("\n\n"))
      reporter.warning(vcreport.summaryString)
      (new PartialSolution(search.g, false).getSolution, false)
    }
  }

  // Returns the new program and the new functions generated for this
  def solutionToProgram(sol: Solution): (Program, Set[FunDef]) = {
    import purescala.TypeTrees.TupleType
    import purescala.Definitions.ValDef

    // Create new fundef for the body
    val ret = TupleType(problem.xs.map(_.getType))
    val res = Variable(FreshIdentifier("res").setType(ret))

    val mapPost: Map[Expr, Expr] =
      problem.xs.zipWithIndex.map{ case (id, i)  =>
        Variable(id) -> TupleSelect(res, i+1)
      }.toMap

    val fd = new FunDef(FreshIdentifier("finalTerm", true), Nil, ret, problem.as.map(id => ValDef(id, id.getType)), DefType.MethodDef)
    fd.precondition  = Some(And(problem.pc, sol.pre))
    fd.postcondition = Some((res.id, replace(mapPost, problem.phi)))
    fd.body          = Some(sol.term)

    val newDefs = sol.defs + fd

    val npr = program.copy(units = program.units map { u =>
      u.copy(modules = ModuleDef(FreshIdentifier("synthesis"), newDefs.toSeq, false) +: u.modules )
    })

    (npr, newDefs)
  }
}

