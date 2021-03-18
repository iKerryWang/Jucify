package lu.uni.trux.jucify.callgraph;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.javatuples.Pair;

import guru.nidi.graphviz.model.Link;
import guru.nidi.graphviz.model.MutableGraph;
import guru.nidi.graphviz.model.MutableNode;
import guru.nidi.graphviz.parse.Parser;
import lu.uni.trux.jucify.instrumentation.DummyBinaryClass;
import lu.uni.trux.jucify.utils.Constants;
import lu.uni.trux.jucify.utils.CustomPrints;
import lu.uni.trux.jucify.utils.Utils;
import soot.Body;
import soot.IntType;
import soot.Local;
import soot.Modifier;
import soot.PatchingChain;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Unit;
import soot.UnitPatchingChain;
import soot.VoidType;
import soot.javaToJimple.LocalGenerator;
import soot.jimple.AssignStmt;
import soot.jimple.IdentityStmt;
import soot.jimple.IfStmt;
import soot.jimple.IntConstant;
import soot.jimple.InvokeExpr;
import soot.jimple.InvokeStmt;
import soot.jimple.Jimple;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.util.dot.DotGraph;

public class CallGraphPatcher {

	private CallGraph cg;
	private int opaquePredicateCount;

	public CallGraphPatcher(CallGraph cg) {
		this.cg = cg;
		this.opaquePredicateCount = 0;
	}

	public void importBinaryCallGraph(List<Pair<String, String>> files) {
		MutableGraph g = null;
		InputStream dot = null;
		String name = null,
				nameTo = null,
				nameFrom = null,
				dotFile = null,
				entrypoints = null;

		SootMethod sm = null,
				m = null,
				from = null,
				to = null;

		Map<String, SootMethod> nodesToMethods = new HashMap<String, SootMethod>();

		try {
			for(Pair<String, String> pair: files) {
				dotFile = pair.getValue0();
				entrypoints = pair.getValue1();
				dot = new FileInputStream(dotFile);
				g = new Parser().read(dot);
				if(dot == null || g == null) {
					CustomPrints.perror("Something wrong with dot file or mutable graph");
					System.exit(1);
				}


				BufferedReader is = new BufferedReader(new FileReader(entrypoints));
				List<Pair<String, SootMethod>> javaToNative = new ArrayList<Pair<String, SootMethod>>();
				Map<String, List<SootMethod>> nativeToJava = new HashMap<String, List<SootMethod>>();
				Stmt stmt = null;
				InvokeExpr ie = null;
				List<SootMethod> javaTargets = null;
				for(String line = is.readLine(); line != null; line = is.readLine()) {
					if(line.startsWith(Constants.HEADER_ENTRYPOINTS_FILE)) {
						continue;
					}
					String[] split = line.split(",");
					String clazz = split[0].trim();
					String method = split[1].trim();
					String sig = split[2].trim();
					String target = split[3].trim();
					Pair<String, String> pairNewSig = Utils.compactSigtoJimpleSig(sig);
					String newSig = Utils.toJimpleSignature(clazz, pairNewSig.getValue1(), method, pairNewSig.getValue0());
					SootMethod nativeMethod = Scene.v().getMethod(newSig);
					for(SootClass sc: Scene.v().getApplicationClasses()) {
						for(SootMethod met: sc.getMethods()) {
							if(met.hasActiveBody()) {
								for(Unit u: met.retrieveActiveBody().getUnits()) {
									stmt = (Stmt) u;
									if(stmt.containsInvokeExpr()) {
										ie = stmt.getInvokeExpr();
										if(ie.getMethod().equals(nativeMethod)) {
											javaToNative.add(new Pair<String, SootMethod>(target, nativeMethod));
										}
									}
								}
							}
						}
					}

					// HANDLE NATIVE TO JAVA CALLS
					if(split.length == 9) {
						String invokeeClass = split[5].trim();
						String invokeeMethod = split[6].trim();
						String invokeeSig = split[7].trim();
						pairNewSig = Utils.compactSigtoJimpleSig(invokeeSig);
						newSig = Utils.toJimpleSignature(invokeeClass, pairNewSig.getValue1(), invokeeMethod, pairNewSig.getValue0());
						sm = Scene.v().getMethod(newSig);
						javaTargets = nativeToJava.get(target);
						if(javaTargets == null) {
							javaTargets = new ArrayList<SootMethod>();
							nativeToJava.put(target, javaTargets);
						}
						javaTargets.add(sm);
					}
				}
				is.close();

				// GENERATE BINARY NODES THAT ARE JAVA NATIVE CALLS AND INSTRUMENT THE BODY
				for(Pair<String, SootMethod> p: javaToNative) {
					if(!nodesToMethods.containsKey(name)) {
						name = p.getValue0();
						m = p.getValue1();
						sm = DummyBinaryClass.v().addBinaryMethod(name,
								m.getReturnType(), m.getModifiers(),
								m.getParameterTypes());
						nodesToMethods.put(name, sm);
						for(SootClass sc: Scene.v().getApplicationClasses()) {
							for(SootMethod met: sc.getMethods()) {
								if(met.hasActiveBody()) {
									Body b = met.retrieveActiveBody();
									UnitPatchingChain units = b.getUnits();
									List<Unit> newUnits = null;
									Stmt point = null;
									for(Unit u: units) {
										stmt = (Stmt) u;
										if(stmt.containsInvokeExpr()) {
											ie = stmt.getInvokeExpr();
											if(ie.getMethod().equals(m)) {
												Pair<Local, Pair<List<Unit>, Stmt>> locNewUnits = this.checkDummyBinaryClassLocalExistence(b, stmt);
												Local dummyBinaryClassLocal = locNewUnits.getValue0();
												Pair<List<Unit>, Stmt> newUnitsPoint = locNewUnits.getValue1();
												if(newUnitsPoint != null) {
													newUnits = newUnitsPoint.getValue0();
													point = newUnitsPoint.getValue1();
												}
												if(stmt instanceof AssignStmt) {
													AssignStmt as = (AssignStmt) stmt;
													if(sm.isStatic()) {
														as.setRightOp(Jimple.v().newStaticInvokeExpr(sm.makeRef(), ie.getArgs()));
													}else if(sm.isConstructor()){
														as.setRightOp(Jimple.v().newSpecialInvokeExpr(dummyBinaryClassLocal, sm.makeRef(), ie.getArgs()));
													}else {
														as.setRightOp(Jimple.v().newVirtualInvokeExpr(dummyBinaryClassLocal, sm.makeRef(), ie.getArgs()));
													}
												}else if(stmt instanceof InvokeStmt) {
													InvokeStmt ivs = (InvokeStmt) stmt;
													if(sm.isStatic()) {
														ivs.setInvokeExpr(Jimple.v().newStaticInvokeExpr(sm.makeRef(), ie.getArgs()));
													}else if(sm.isConstructor()){
														ivs.setInvokeExpr(Jimple.v().newSpecialInvokeExpr(dummyBinaryClassLocal, sm.makeRef(), ie.getArgs()));
													}else {
														ivs.setInvokeExpr(Jimple.v().newVirtualInvokeExpr(dummyBinaryClassLocal, sm.makeRef(), ie.getArgs()));
													}
												}
											}
											// Modify native call to newly generated call + add edge to call-graph
											if(ie.getMethod().equals(m)) {
												ie.setMethodRef(sm.makeRef());
												this.cg.addEdge(new Edge(met, stmt, sm));
												CustomPrints.pinfo(String.format("Adding java-to-native Edge from %s to %s", met, sm));
											}
										}
									}
									if(newUnits != null && point != null) {
										units.insertBefore(newUnits, point);
										b.validate();
									}
								}
							}
						}
						// Handle Native to Java
						javaTargets = nativeToJava.get(name);
						if(javaTargets != null && !javaTargets.isEmpty()) {
							for(SootMethod met: javaTargets) {
								Type ret = met.getReturnType();
								Body b = sm.retrieveActiveBody();
								Local local = null;
								LocalGenerator lg = new LocalGenerator(b);
								for(Local l: b.getLocals()) {
									if(l.getType().equals(met.getDeclaringClass().getType())) {
										local = l;
										break;
									}
								}
								if(local == null) {
									local = lg.generateLocal(met.getDeclaringClass().getType());
								}
								if(met.isConstructor()) {
									ie = Jimple.v().newSpecialInvokeExpr(local, met.makeRef());
								}else if(met.isStatic()) {
									ie = Jimple.v().newStaticInvokeExpr(met.makeRef());
								}else {
									ie = Jimple.v().newVirtualInvokeExpr(local, met.makeRef());
								}
								Stmt newStmt = null;
								if(ret.equals(VoidType.v())) {
									newStmt = Jimple.v().newInvokeStmt(ie);
								}else {
									local = lg.generateLocal(met.getReturnType());
									newStmt = Jimple.v().newAssignStmt(local, ie);
								}
								if(newStmt != null) {
									Unit firstAfterIdenditiesUnits = this.getfirstAfterIdenditiesUnits(b);
									b.getUnits().insertBefore(newStmt, firstAfterIdenditiesUnits);
								}
								Edge e = new Edge(sm, newStmt, met);
								this.cg.addEdge(e);
								CustomPrints.pinfo(String.format("Adding native-to-java Edge from %s to %s", sm, met));

								if(!ret.equals(VoidType.v())) {
									// FIX MULTIPLE RETURN OF SAME TYPE (OPAQUE PREDICATE)
									final Local retLoc = local;
									final PatchingChain<Unit> units = b.getUnits();
									Unit last = units.getLast();
									Local opaquePredicateLocal = checkOpaquePredicateLocalExistence(b);
									IfStmt ifStmt = Jimple.v().newIfStmt(Jimple.v().newEqExpr(opaquePredicateLocal, IntConstant.v(opaquePredicateCount++)), last);
									units.insertBefore(ifStmt, last);
									ifStmt.setTarget(last);
									units.insertAfter(Jimple.v().newReturnStmt(retLoc), ifStmt);
									b.validate();
								}
							}
						}
					}
				}

				// GENERATE BINARY NODES INTO SOOT CALL GRAPH
				for(MutableNode node: g.nodes()) {
					name = Utils.removeNodePrefix(node.name().toString());
					if(!nodesToMethods.containsKey(name)) {
						sm = DummyBinaryClass.v().addBinaryMethod(name, VoidType.v(), Modifier.PUBLIC, new ArrayList<>());
						nodesToMethods.put(name, sm);
					}
				}

				// ADD EDGE FROM INITIAL BINARY CALL-GRAPH
				for(Link l: g.edges()) {
					nameFrom = Utils.removeNodePrefix(l.from().name().toString());
					nameTo = Utils.removeNodePrefix(l.to().name().toString());
					from = nodesToMethods.get(nameFrom);
					to = nodesToMethods.get(nameTo);
					stmt = (Stmt) Utils.addMethodCall(from, to);
					if(stmt != null) {
						Edge e = new Edge(from, stmt, to);
						this.cg.addEdge(e);
					}
				}
			}

		} catch (IOException e) {
			CustomPrints.perror(e.getMessage());
			System.exit(1);
		}
	}

	private Unit getfirstAfterIdenditiesUnits(Body b) {
		UnitPatchingChain units = b.getUnits();
		Unit u = null;
		Iterator<Unit> it = units.iterator();
		u = it.next();
		while(u instanceof IdentityStmt) {
			u = it.next();
		}
		return u;
	}

	private Pair<Local, Pair<List<Unit>, Stmt>> checkDummyBinaryClassLocalExistence(Body b, Stmt stmt) {
		for(Local l: b.getLocals()) {
			if(l.getType().equals(RefType.v(Constants.DUMMY_BINARY_CLASS))) {
				return new Pair<Local, Pair<List<Unit>,Stmt>>(l, null);
			}
		}
		List<Unit> unitsToAdd = new ArrayList<Unit>();
		LocalGenerator lg = new LocalGenerator(b);
		Local local = lg.generateLocal(RefType.v(Constants.DUMMY_BINARY_CLASS));
		unitsToAdd.add(Jimple.v().newAssignStmt(local, Jimple.v().newNewExpr(RefType.v(Constants.DUMMY_BINARY_CLASS))));
		unitsToAdd.add(Jimple.v().newInvokeStmt(
				Jimple.v().newSpecialInvokeExpr(local,
						Utils.getMethodRef(Constants.DUMMY_BINARY_CLASS, Constants.INIT_METHOD_SUBSIG))));
		Pair<List<Unit>, Stmt> p1 = new Pair<List<Unit>, Stmt>(unitsToAdd, stmt);
		Pair<Local, Pair<List<Unit>, Stmt>> p2 = new Pair<Local, Pair<List<Unit>, Stmt>>(local, p1);
		return p2;
	}

	private Local checkOpaquePredicateLocalExistence(Body b) {
		for(Local l: b.getLocals()) {
			if(l.getType().equals(IntType.v()) && l.getName().equals(Constants.OPAQUE_PREDICATE_LOCAL)) {
				return l;
			}
		}
		Local loc = Jimple.v().newLocal(Constants.OPAQUE_PREDICATE_LOCAL, IntType.v());
		b.getLocals().add(loc);
		return loc;
	}

	public void dotifyCallGraph(String destination) {
		DotGraph dg = new DotGraph(Constants.GRAPH_NAME);
		Iterator<Edge> it = this.cg.iterator();
		Edge next = null;
		while(it.hasNext()) {
			next = it.next();
			dg.drawEdge(next.src().getName(), next.tgt().getName());
		}
		dg.plot(destination);
	}
}
