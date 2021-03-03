package lu.uni.trux.jucify;

import lu.uni.trux.jucify.callgraph.CallGraphPatcher;
import lu.uni.trux.jucify.utils.CommandLineOptions;
import soot.Scene;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.toolkits.callgraph.CallGraph;

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

public class Main {
	public static void main(String[] args) throws Throwable {
		CommandLineOptions options = new CommandLineOptions(args);
		String apk = options.getApk(),
			   platforms = options.getPlatforms(),
			   dotFile = options.getDot();
		InfoflowAndroidConfiguration ifac = new InfoflowAndroidConfiguration();
		ifac.getAnalysisFileConfig().setAndroidPlatformDir(platforms);
		ifac.getAnalysisFileConfig().setTargetAPKFile(apk);
		SetupApplication sa = new SetupApplication(ifac);
		sa.constructCallgraph();
		CallGraph cg = Scene.v().getCallGraph();
		
		System.out.println(String.format("Loading %s...", dotFile));
		CallGraphPatcher cgp = new CallGraphPatcher(cg);
		cgp.importBinaryCallGraph(dotFile);
		System.out.println("Binary callgraph imported.");
		
		if(options.hasExportCallGraph()) {
			String destination = options.getExportCallGraphDestination();
			System.out.println(String.format("Exporting call graph to %s...", destination));
			cgp.dotifyCallGraph(destination);
			System.out.println("Callgraph exported.");
		}
		
		
		
	}
}