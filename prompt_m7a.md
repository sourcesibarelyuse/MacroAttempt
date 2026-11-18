Write M7a: static validation pipeline for the macro DSL IR, ONE pure Kotlin file, package com.macroengine.dsl, zero Android imports. Output ONLY Kotlin code; explanations in // comments. Reuse (do NOT redeclare) M6_Dsl.kt types: Span, Op and its nested Call/Loop/If/Action/Cond/Break/Continue/Return/Log, TargetDecl, Meta, FlowIr. Define your own private assertTrue/assertEq and a private check(name){} harness. No raw strings.
Include:
- data class ValidationFault(code: String, message: String, span: Span); data class ValidationReport(errors: List<ValidationFault>, warnings: List<ValidationFault>) with val ok = errors.isEmpty()
- fun validate(ir: FlowIr, grantedCaps: Set<String>): ValidationReport doing in order:
 1 RESOLVE: Op.Call.ref must bind to ir.subs keys (UNBOUND_SUB); selector names inside Action args (bare name or kind:name) must bind to ir.targets names (UNBOUND_TARGET); duplicate loop labels => DUP_LABEL.
 2 TYPECHECK: signature table tap/long_press/double_tap/wait_for/wait_until = exactly 1 selector arg; swipe >= 1; log = 1; unknown action => UNKNOWN_ACTION; If.cond starting with a command name => CMD_AS_COND.
 3 CFG: Break/Continue outside any loop => BREAK_OUTSIDE_LOOP; statement after Return/Break/Continue at same block level => UNREACHABLE; sub call graph acyclic else SUB_CYCLE.
 4 CAPABILITY PREFLIGHT (warnings): selector kinds img/text/region need SCREEN_CAPTURE; ocr needs OCR; tap/swipe/long_press/double_tap need INPUT_INJECT; missing => CAP_MISSING warning with span.
 5 RESOURCE BUDGET (warnings): loop maxIter==null => UNBOUNDED_LOOP; meta.timeoutMs<=0 => NO_FLOW_TIMEOUT; nested-max product > 100000 => BUDGET_CEILING.
- fun main(): table-driven tests over hand-built FlowIr values (construct Op/FlowIr directly, no parsing): valid flow ok; unbound sub; unbound target; break outside loop; unreachable after return; sub cycle a->b->a; CAP_MISSING when SCREEN_CAPTURE absent with img target; UNBOUNDED_LOOP warning; spans preserved. Print "ALL M7A TESTS PASSED".
ADDITIONAL HARD CONSTRAINTS (fix compile + logic failures):
- EXACT M6_Dsl.kt signatures, use nothing else:
  Op.Call(ref: String, span: Span)  // NO args field
  Op.Loop(label: String?, everyMs: Long, maxIter: Int?, body: List<Op>, span: Span)
  Op.If(cond: String, thenBody: List<Op>, elseBody: List<Op>, span: Span)  // elseBody NON-nullable, do not use ?: on it
  Op.Action(call: String, args: List<String>, timeoutMs: Long?, onTimeout: List<Op>, onFail: List<Op>, span: Span)  // field is `call`, NOT `name`
  Op.Cond(expr: String, span: Span); Op.Break(span: Span); Op.Continue(span: Span); Op.Return(span: Span); Op.Log(msg: String, span: Span)
  TargetDecl(name: String, kind: String, value: String, confidence: Double?, span: Span)
  Meta(name: String, timeoutMs: Long, requireCaps: List<String>)
  FlowIr(irVersion: Int, flowId: String, meta: Meta, targets: List<TargetDecl>, body: List<Op>, subs: Map<String, List<Op>>)
- In validate(), read the action name as op.call. Never read op.args from Op.Call (it has none).
- Selector binding applies ONLY to selector-taking actions (tap, long_press, double_tap, wait_for, wait_until, swipe). Op.Log args are messages: never treat them as selectors, never require them to bind targets.
- Tests must construct every Op/Meta/TargetDecl/FlowIr with the exact signatures above (Loop needs everyMs; Meta needs name+requireCaps; FlowIr needs irVersion+flowId; TargetDecl needs value+confidence+span).
- Output ONLY Kotlin code; explanations in // comments; no raw strings.
