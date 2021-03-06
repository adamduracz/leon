/* Copyright 2009-2014 EPFL, Lausanne */

package leon
package test
package synthesis

import leon._
import leon.purescala.Definitions._
import leon.purescala.Common._
import leon.purescala.Trees._
import leon.purescala.ScalaPrinter
import leon.purescala.PrinterContext
import leon.purescala.PrinterOptions
import leon.purescala.TreeOps._
import leon.solvers.z3._
import leon.solvers.Solver
import leon.synthesis._
import leon.synthesis.utils._

import scala.collection.mutable.Stack
import scala.io.Source

import java.io.{File, BufferedWriter, FileWriter}

class StablePrintingSuite extends LeonTestSuite {
  private def forEachFileIn(path : String)(block : File => Unit) {
    val fs = filesInResourceDir(path, _.endsWith(".scala"))

    for(f <- fs) {
      block(f)
    }
  }


  private def testIterativeSynthesis(cat: String, f: File, depth: Int) {

    def getChooses(ctx: LeonContext, content: String): (Program, Seq[ChooseInfo]) = {
      val opts = SynthesisOptions()
      val pipeline = leon.utils.TemporaryInputPhase andThen 
                     frontends.scalac.ExtractionPhase andThen
                     leon.utils.PreprocessingPhase andThen
                     purescala.FunctionClosure

      val program = pipeline.run(ctx)((content, Nil))

      (program, ChooseInfo.extractFromProgram(ctx, program, opts))
    }

    case class Job(content: String, choosesToProcess: Set[Int], rules: List[String]) {
      def info(task: String): String = {
        val r = if (rules.isEmpty) "<init>" else "after "+rules.head

        val indent = "  "*(rules.size)+" "

        f"${indent+r}%-40s [$task%s]"
      }
    }


    test(cat+": "+f.getName+" - Synthesis <-> Print (depth="+depth+")") {
      val res = Source.fromFile(f).mkString

      val workList = Stack[Job](Job(res, Set(), Nil))

      while(!workList.isEmpty) {
        val reporter = new TestSilentReporter
        val ctx = createLeonContext("--synthesis").copy(reporter = reporter)
        val j = workList.pop()

        info(j.info("compilation"))

        val (pgm, chooses) = try {
          getChooses(ctx, j.content)
        } catch {
          case e: Throwable =>
            val contentWithLines = j.content.split("\n").zipWithIndex.map{ case (l, i) => f"${i+1}%4d: $l"}.mkString("\n")
            info("Error while compiling:\n"+contentWithLines)
            for (e <- reporter.lastErrors) {
              info(e)
            }
            info(e.getMessage)
            fail("Compilation failed")
        }

        if (j.rules.size < depth) {
          for ((ci, i) <- chooses.zipWithIndex if j.choosesToProcess(i) || j.choosesToProcess.isEmpty) {
            val sctx = SynthesisContext.fromSynthesizer(ci.synthesizer)
            val problem = ci.problem
            info(j.info("synthesis "+problem))
            val apps = Rules.getInstantiations(sctx, problem)

            for (a <- apps) {
              a.apply(sctx) match {
                case RuleClosed(sols) =>
                case RuleExpanded(sub) =>
                  a.onSuccess(sub.map(Solution.choose(_))) match {
                    case Some(sol) =>
                      val result = sol.toSimplifiedExpr(ctx, pgm)

                      val newContent = new FileInterface(ctx.reporter).substitute(j.content, ci.ch, (indent: Int) => {
                        val p = new ScalaPrinter(PrinterOptions())
                        p.pp(result)(PrinterContext(result, Some(ci.fd), Some(ci.fd), indent, p))
                        p.toString
                      })

                      workList push Job(newContent, (i to i+sub.size).toSet, a.toString :: j.rules)
                    case None =>
                  }
              }
            }
          }
        }
      }
    }
  }



  forEachFileIn("regression/synthesis/Church/") { f =>
    testIterativeSynthesis("Church", f, 1)
  }

  forEachFileIn("regression/synthesis/List/") { f =>
    testIterativeSynthesis("List", f, 1)
  }
}
