package edu.tamu.aser.tide.akkasys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.propagation.PointerKey;
import com.ibm.wala.util.intset.MutableIntSet;

import akka.actor.UntypedActor;
import edu.tamu.aser.tide.engine.AstCGNode2;
import edu.tamu.aser.tide.engine.ITIDEBug;
import edu.tamu.aser.tide.engine.TIDECGModel;
import edu.tamu.aser.tide.engine.TIDEDeadlock;
import edu.tamu.aser.tide.engine.TIDEEngine;
import edu.tamu.aser.tide.engine.TIDERace;
import edu.tamu.aser.tide.nodes.DLPair;
import edu.tamu.aser.tide.nodes.DLockNode;
import edu.tamu.aser.tide.nodes.DUnlockNode;
import edu.tamu.aser.tide.nodes.INode;
import edu.tamu.aser.tide.nodes.JoinNode;
import edu.tamu.aser.tide.nodes.LockPair;
import edu.tamu.aser.tide.nodes.MemNode;
import edu.tamu.aser.tide.nodes.ReadNode;
import edu.tamu.aser.tide.nodes.StartNode;
import edu.tamu.aser.tide.nodes.WriteNode;
import edu.tamu.aser.tide.shb.SHBEdge;
import edu.tamu.aser.tide.shb.SHBGraph;
import edu.tamu.aser.tide.shb.Trace;
import edu.tamu.aser.tide.tests.Test;

public class BugWorker extends UntypedActor{

	private final static boolean DEBUG = false;


	@Override
	public void onReceive(Object message) throws Throwable {
		//initial
		if(message instanceof FindSharedVarJob){
			FindSharedVarJob job = (FindSharedVarJob) message;
			processFindSharedVarJob(job);
		}else if(message instanceof RemoveLocalJob){
			RemoveLocalJob job = (RemoveLocalJob) message;
			processRemoveLocalJob(job);
		}else if(message instanceof CheckDatarace){
			CheckDatarace job = (CheckDatarace) message;
			processCheckDatarace(job);
		}else if(message instanceof CheckDeadlock){
			CheckDeadlock job = (CheckDeadlock) message;
			processCheckDeadlock(job);
		}
		else if(message instanceof IncreRecheckCommonLock){
			IncreRecheckCommonLock job = (IncreRecheckCommonLock) message;
			processIncreRecheckCommonLocks(job);
		}
		else{
			unhandled(message);
		}
	}



	private TIDEDeadlock checkDeadlock(DLPair dllp1, DLPair dllp2, int tid1, int tid2) {
		HashSet<String> l11sig = dllp1.lock1.getLockSig();
		HashSet<String> l12sig = dllp1.lock2.getLockSig();
		HashSet<String> l21sig = dllp2.lock1.getLockSig();
		HashSet<String> l22sig = dllp2.lock2.getLockSig();
		if(containAny(l11sig, l22sig) && containAny(l21sig, l12sig)){
			//check reachability
			boolean reached = hasHBRelation(tid1, dllp1.lock1, tid2, dllp2.lock1);
			if(reached){
				TIDEDeadlock dl = new TIDEDeadlock(tid1,dllp1, tid2,dllp2);
				boolean isReentrant = false;
				if((l11sig.equals(l12sig)) || (l21sig.equals(l22sig))){
					//maybe reentrant lock, check pointer again
					PointerKey l11key = dllp1.lock1.getPointer();
					PointerKey l12key = dllp1.lock2.getPointer();
					PointerKey l21key = dllp2.lock1.getPointer();
					PointerKey l22key = dllp2.lock2.getPointer();
					if(l11key.equals(l12key) || l21key.equals(l22key)){
						isReentrant = true;
					}
				}
				if(!isReentrant){
					return dl;
				}
			}
		}
		return null;
	}


	private void processCheckDeadlock(CheckDeadlock job) {
		TIDEEngine engine;
		if(DEBUG){
			engine = Test.engine;
		}else{
			engine = TIDECGModel.bugEngine;
		}
		HashSet<ITIDEBug> bugs = new HashSet<ITIDEBug>();
		ArrayList<DLPair> dLLockPairs = job.getPairs();
		Set<Integer> tids = job.getTids();
		int tid1 = job.getTid();
		for(int i=0;i<dLLockPairs.size();i++){
			DLPair dllp1 = dLLockPairs.get(i);
			for(Integer tid2: tids){
				if(tid2!=tid1){
					ArrayList<DLPair> dLLockPairs2 = engine.threadDLLockPairs.get(tid2);
					for(int j=0;j<dLLockPairs2.size();j++){
						DLPair dllp2 = dLLockPairs2.get(j);
						TIDEDeadlock dl = checkDeadlock(dllp1, dllp2, tid1, tid2);
						if (dl != null) {
							bugs.add(dl);
						}
					}
				}
			}
		}
		if(bugs.size() > 0){
			engine.addBugsBack(bugs);
		}
		getSender().tell(new ReturnResult(), getSelf());

	}



	private boolean containAny(HashSet<String> sigs1, HashSet<String> sigs2) {
		for (String sig2 : sigs2) {
			if(sigs1.contains(sig2)){
				return true;
			}
		}
		return false;
	}



	private void processCheckDatarace(CheckDatarace job) {
		HashSet<WriteNode> writes = job.getWrites();
		HashSet<ReadNode> reads = job.getReads();
		HashSet<ITIDEBug> bugs = new HashSet<ITIDEBug>();
		String sig = job.getSig();
		TIDEEngine engine;
		if(DEBUG){
			engine = Test.engine;
		}else{
			engine = TIDECGModel.bugEngine;
		}
		SHBGraph shb = engine.shb;
	    	//check read & write
	    for (WriteNode wnode : writes) {
	    		Trace wTrace = shb.getTrace(wnode.getBelonging());
			if (wTrace == null) {
				continue;
			}
			ArrayList<Integer> wtids = wTrace.getTraceTids();
	    		if(reads!=null){
				//write->read
	    			for(ReadNode read : reads){
	    				System.err.println("---read: " + read);
	    				MemNode xnode = read;
	    				Trace xTrace = shb.getTrace(xnode.getBelonging());
	    				if (xTrace == null) {//this xnode shoudl be deleted already!!!!!!
						continue;
					}
					ArrayList<Integer> xtids = xTrace.getTraceTids();
					for (int wtid : wtids) {
						for (int xtid : xtids) {
							if(checkLockSetAndHappensBeforeRelation(wtid, wnode, xtid, xnode)){
								System.out.println("+++++race detected!!");
								TIDERace race = new TIDERace(sig,xnode,xtid,wnode, wtid);
								bugs.add(race);
							}
						}
					}
	    			}
	    		}
	    		//write->write
	    		for(WriteNode write : writes){
	    			System.err.println("---write: " + write);
	    			WriteNode xnode = write;
	    			Trace xTrace = shb.getTrace(xnode.getBelonging());
	    			if (xTrace == null) {
	    				continue;
				}
				ArrayList<Integer> xtids = xTrace.getTraceTids();
				for (int wtid : wtids) {
					for (int xtid : xtids) {
						if(checkLockSetAndHappensBeforeRelation(xtid, xnode, wtid, wnode)){
							System.out.println("+++++race detected!!");
							TIDERace race = new TIDERace(sig,xnode, xtid, wnode, wtid);
							bugs.add(race);
						}
					}
				}
	    		}
	    }

	    if(bugs.size() > 0){
	    	engine.addBugsBack(bugs);
		}
		getSender().tell(new ReturnResult(), getSelf());
	}


	private void filterRWNodesBySig(HashSet<String> sigs, String sig, INode node,
			HashMap<String, HashSet<ReadNode>> sigReadNodes, HashMap<String, HashSet<WriteNode>> sigWriteNodes) {
		if(sigs.contains(sig)){
			if (node instanceof ReadNode) {
				HashSet<ReadNode> reads = sigReadNodes.get(sig);
				if(reads==null){
					reads = new HashSet<ReadNode> ();
					reads.add((ReadNode) node);
					sigReadNodes.put(sig, reads);
				}else{
					reads.add((ReadNode)node);
				}
			}else{//write node
				HashSet<WriteNode> writes = sigWriteNodes.get(sig);
				if(writes==null){
					writes = new HashSet<WriteNode> ();
					writes.add((WriteNode)node);
					sigWriteNodes.put(sig, writes);
				}else{
					writes.add((WriteNode)node);
				}
			}
		}
	}



	private void processRemoveLocalJob(RemoveLocalJob job) {
		ArrayList<Trace> team = job.getTeam();
		TIDEEngine engine;
		if(DEBUG){
			engine = Test.engine;
		}else{
			engine = TIDECGModel.bugEngine;
		}
		HashSet<String> sharedFields = engine.sharedFields;
		HashMap<String, HashSet<ReadNode>> sigReadNodes = new HashMap<String, HashSet<ReadNode>>();
		HashMap<String, HashSet<WriteNode>> sigWriteNodes = new HashMap<String, HashSet<WriteNode>>();

		for(int i=0; i<team.size(); i++){
			Trace trace = team.get(i);
			ArrayList<INode> nodes = trace.getContent();
			for (INode node : nodes) {
				if(node instanceof MemNode){
					HashSet<String> sigs = ((MemNode)node).getObjSig();
					for (String sig : sigs) {
						filterRWNodesBySig(sharedFields, sig, node, sigReadNodes, sigWriteNodes);
					}
				}
			}
		}
		engine.addSigReadNodes(sigReadNodes);
		engine.addSigWriteNodes(sigWriteNodes);
		getSender().tell(new ReturnResult(), getSelf());
	}

	private void processFindSharedVarJob(FindSharedVarJob job) {
		HashSet<String> sharedFields = new HashSet<>();
		String sig = job.getSig();
		HashMap<Integer, Integer> readMap = job.getReadMap();
		HashMap<Integer, Integer> writeMap = job.getWriteMap();
		int writeTids = writeMap.size();
		if(writeTids > 1){
			sharedFields.add(sig);
		}else{
			if(readMap != null){
				int readTids = readMap.size();
				if (readTids + writeTids > 1) {
					sharedFields.add(sig);
				}
			}
		}
		if (DEBUG) {
			Test.engine.addSharedVars(sharedFields);
		}else{
			TIDECGModel.bugEngine.addSharedVars(sharedFields);
		}
		getSender().tell(new ReturnResult(), getSelf());
	}

	private boolean checkLockSetAndHappensBeforeRelation(Integer wtid, WriteNode wnode, Integer xtid, MemNode xnode) {//ReachabilityEngine reachEngine,
		TIDEEngine engine;
		if(DEBUG){
			engine = Test.engine;
		}else{
			engine = TIDECGModel.bugEngine;
		}
		if(wtid != xtid){
			if(!haveCommonLock(xtid, xnode, wtid, wnode)){
				return hasHBRelation(wtid, wnode, xtid, xnode);
			}
		}
		return false;
	}

	private void processIncreRecheckCommonLocks(IncreRecheckCommonLock job){
		TIDEEngine engine;
		if(DEBUG){
			engine = Test.engine;
		}else{
			engine = TIDECGModel.bugEngine;
		}
		HashSet<TIDERace> group = job.getGroup();
		//has common lock, update the race inside lock
		HashSet<TIDERace> recheckRaces = engine.recheckRaces;
		HashSet<TIDERace> removes = new HashSet<>();
		for (TIDERace check : group) {
			for (ITIDEBug bug : recheckRaces) {
				if(bug instanceof TIDERace){
					TIDERace exist = (TIDERace) bug;
					if (exist.equals(check)) {
						removes.add(check);
					}
				}
			}
		}

		if (removes.size() > 0) {
			engine.removeBugs(removes);
		}
		getSender().tell(new ReturnResult(), getSelf());
	}

	private boolean haveCommonLock(int xtid, INode xnode, int wtid, INode wnode) {
		//inode location
		HashMap<LockPair, INode> xpair_edge_locations = new HashMap<>();
		HashMap<LockPair, INode> wpair_edge_locations = new HashMap<>();
		//get all the lockpair on the path
		HashMap<String, ArrayList<LockPair>> xAllPairs = collectAllLockPairsFor(xtid, xnode, xpair_edge_locations);
		HashMap<String, ArrayList<LockPair>> wAllPairs = collectAllLockPairsFor(wtid, wnode, wpair_edge_locations);
		//check common lock
		for (String sig : xAllPairs.keySet()) {
			ArrayList<LockPair> xPairs = xAllPairs.get(sig);
			ArrayList<LockPair> wPairs = wAllPairs.get(sig);
			if (xPairs!= null && wPairs != null) {
				//TODO: because of 1-objectsensitive, in bubblesort/OneBubble/SwapConsecutives:
				//this check will consider the sync on _threadCounterLock as a common lock => TN
				boolean xhas = doesHaveLockBetween(xnode, xPairs, xpair_edge_locations);
				boolean whas = doesHaveLockBetween(wnode, wPairs, wpair_edge_locations);
				if(xhas && whas){
					return true;
				}
			}
		}
		return false;
	}

	private HashMap<String, ArrayList<LockPair>> collectAllLockPairsFor(int tid, INode node,
			HashMap<LockPair, INode> pair_edge_locations) {
		HashMap<String, ArrayList<LockPair>> allPairs = new HashMap<>();
		SHBGraph shb;
		if(DEBUG){
			shb = Test.engine.shb;
		}else{
			shb = TIDECGModel.bugEngine.shb;
		}
		//current; recursive
		int round = 5;//can be changed
		while(round >= 0){
			CGNode cgNode = node.getBelonging();
			Trace trace = shb.getTrace(cgNode);
			if(trace == null){
				if (cgNode instanceof AstCGNode2) {
					cgNode = ((AstCGNode2) cgNode).getCGNode();
					trace = shb.getTrace(cgNode);
				}
			}
			ArrayList<LockPair> pairs = trace.getLockPair();
			for (LockPair pair : pairs) {
				pair_edge_locations.put(pair, node);
				HashSet<String> sigs = ((DLockNode) pair.lock).getLockSig();
				for (String sig : sigs) {
					ArrayList<LockPair> exists = allPairs.get(sig);
					if(exists == null){
						exists = new ArrayList<>();
						exists.add(pair);
						allPairs.put(sig, exists);
					}else{
						exists.add(pair);
					}
				}
			}
			SHBEdge edge = shb.getIncomingEdgeWithTidForShowTrace(cgNode, tid);//using dfs, since usually is single tid shbedge
			if (edge == null) {
				break;
			}else{
				node = edge.getSource();
				round--;
			}
		}

		return allPairs;
	}



	private boolean doesHaveLockBetween(INode check, ArrayList<LockPair> pairs,
			HashMap<LockPair, INode> pair_edge_locations) {
		SHBGraph shb;
		if(DEBUG){
			shb = Test.engine.shb;
		}else{
			shb = TIDECGModel.bugEngine.shb;
		}
		for (LockPair pair : pairs) {
			INode node = pair_edge_locations.get(pair);
			DLockNode lock = (DLockNode) pair.lock;
			DUnlockNode unlock = (DUnlockNode) pair.unlock;
			Trace trace = shb.getTrace(node.getBelonging());
			int idxL = trace.indexOf(lock);
			int idxN = trace.indexOf(node);
			int idxU = trace.indexOf(unlock);
			if (idxL < idxN && idxN < idxU) {
				return true;
			}
		}
		return false;
	}



	private StartNode sameParent(int tid1, int tid2) {
		HashMap<Integer, StartNode> mapOfStartNode;
		if(DEBUG){
			mapOfStartNode = Test.engine.mapOfStartNode;
		}else{
			mapOfStartNode = TIDECGModel.bugEngine.mapOfStartNode;
		}
		Iterator<Integer> iter_thread = mapOfStartNode.keySet().iterator();
		while(iter_thread.hasNext()){
			int t = iter_thread.next();
			if(t != tid1 && t != tid2){
				MutableIntSet kids = mapOfStartNode.get(t).getTID_Child();
				if(kids.contains(tid1) && kids.contains(tid2)){
					return mapOfStartNode.get(t);
				}
			}
		}
		return null;
	}


	private boolean hasHBRelation(int erTID, INode comper, int eeTID, INode compee){
		boolean donothave = false;
		TIDEEngine engine;
		if(DEBUG){
			engine = Test.engine;
		}else{
			engine = TIDECGModel.bugEngine;
		}
		SHBGraph shb = engine.shb;
		CallGraph cg = engine.callGraph;
		StartNode erStartNode = engine.mapOfStartNode.get(erTID);
		StartNode eeStartNode = engine.mapOfStartNode.get(eeTID);
		JoinNode erJoinNode = engine.mapOfJoinNode.get(erTID);
		JoinNode eeJoinNode = engine.mapOfJoinNode.get(eeTID);

		if (erStartNode == null || eeStartNode == null) {
			return false;//should not be?? the startnode has been removed, but the rwnode still got collected.
		}
		MutableIntSet erkids = erStartNode.getTID_Child();
		MutableIntSet eekids = eeStartNode.getTID_Child();
		// -1: sync -> comper; 1: comper -> sync; 0: ?
		if(erkids.contains(eeTID)){
			//wtid is parent of xtid, wtid = comper
			if(shb.compareParent(eeStartNode, comper, eeTID, erTID) < 0){//trace.indexOf(xStartNode) < trace.indexOf(comper)
				if (eeJoinNode != null) {
					if (shb.compareParent(eeJoinNode, comper, eeTID, erTID) > 0) {//trace.indexof(xjoinnode) > trace.indexof(comper)
						donothave = true; //for multipaths: what if the paths compared above are different?
					}
				}else{
					donothave = true;
				}
			}
		}else if(eekids.contains(erTID)){
			//xtid is parent of wtid, xtid = compee
			if(shb.compareParent(erStartNode, compee, eeTID, erTID) < 0){//trace.indexOf(wStartNode) < trace.indexOf(compee)
				if (erJoinNode != null) {
					if(shb.compareParent(erJoinNode, compee, eeTID, erTID) > 0){////trace.indexof(wjoinnode) > trace.indexof(compee)
						donothave = true;
					}
				}else {
					donothave = true;
				}
			}
		}else{
			StartNode sNode = sameParent(erTID, eeTID);
			if(sNode != null){
				CGNode parent;
				if (sNode.getParentTID() == -1) {
					parent = sNode.getTarget();//main
				}else{
					parent = sNode.getBelonging();
				}
				//same parent
				if(erJoinNode == null && eeJoinNode == null){
					//should check the distance
					Trace ptTrace = shb.getTrace(parent);//maybe mark the relation??
					int erS = ptTrace.indexOf(erStartNode);
					int eeS = ptTrace.indexOf(eeStartNode);
					if (Math.abs(erS - eeS) <= 1000) {//adjust??
						donothave = true;
					}
				}else if(erJoinNode == null){//-1: start -> join; 1: join -> start;
					if(shb.compareStartJoin(erStartNode, eeJoinNode, parent, cg) < 0){//trace.indexOf(xJoinNode) > trace.indexOf(wStartNode)
						donothave = true;
					}
				}else if(eeJoinNode == null){
					if(shb.compareStartJoin(eeStartNode, erJoinNode, parent, cg) < 0){//trace.indexOf(wJoinNode) > trace.indexOf(xStartNode)
						donothave = true;
					}
				}else{
					if(shb.compareStartJoin(erStartNode, eeJoinNode, parent, cg) < 0
							&& shb.compareStartJoin(eeStartNode, erJoinNode, parent, cg) < 0){//(trace.indexOf(xJoinNode) > trace.indexOf(wStartNode)) && (trace.indexOf(wJoinNode) > trace.indexOf(xStartNode))
						donothave = true;
					}
				}
			}else{
				//other conditions??wtid = comper; xtid = compee
				if(shb.whoHappensFirst(erStartNode, eeStartNode, eeTID, erTID) < 0){//trace.indexOf(wStartNode) < trace.indexOf(xStartNode)
					//wtid starts early
					if(shb.whoHappensFirst(erStartNode, comper, eeTID, erTID) < 0){
						donothave = true;
					}
				}else{
					//xtid starts early
					if(shb.whoHappensFirst(eeStartNode, compee, eeTID, erTID) < 0){
						donothave = true;
					}
				}
			}
		}
		return donothave;
	}




}
