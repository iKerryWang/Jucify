package lu.uni.trux.jucify.utils;

import soot.Body;
import soot.Local;
import soot.PatchingChain;
import soot.Scene;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Type;
import soot.Unit;
import soot.jimple.Jimple;
import soot.jimple.ReturnVoidStmt;

/*-
 * #%L
 * JuCify
 * 
 * %%
 * Copyright (C) 2021 Jordan Samhi
 * University of Luxembourg - Interdisciplinary Centre for
 * Security Reliability and Trust (SnT) - TruX - All rights reserved
 *
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

public class Utils {
	
	private static int localNum = 0;

	public static String removeNodePrefix(String s) {
		if(s.startsWith(Constants.NODE_PREFIX)) {
			return s.substring(5, s.length());
		}
		return s;
	}
	
	public static Local addLocalToBody(Body b, Type t) {
		Local l = Jimple.v().newLocal(getNextLocalName(), t);
		b.getLocals().add(l);
		return l;
	}
	
	private static String getNextLocalName() {
		return "loc"  + localNum++;
	}
	
	public static SootMethodRef getMethodRef(String className, String methodName) {
		return Scene.v().getSootClass(className).getMethod(methodName).makeRef();
	}
	
	public static Unit addMethodCall(SootMethod caller, SootMethod callee) {
		Body b = caller.retrieveActiveBody();
		final PatchingChain<Unit> units = b.getUnits();
		ReturnVoidStmt stmt = null;
		for(Unit u: units) {
			if(u instanceof ReturnVoidStmt) {
				stmt = (ReturnVoidStmt) u;
				Local thisLocal = b.getThisLocal();
				Unit newUnit = Jimple.v().newInvokeStmt(
						Jimple.v().newSpecialInvokeExpr(thisLocal,
								Utils.getMethodRef(Constants.DUMMY_BINARY_CLASS, callee.getSubSignature())));
				units.insertBefore(newUnit, stmt);
				return newUnit;
			}
		}
		return null;
	}
	
}