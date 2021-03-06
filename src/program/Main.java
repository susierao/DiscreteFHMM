package program;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import model.HMMBase;
import model.HMMNoFinalStateLog;
import model.HMMPowModel;
import model.train.EM;
import util.Timing;
import config.Config;
import config.LastIter;
import corpus.Corpus;
import corpus.Instance;
import corpus.InstanceList;

public class Main {
	static HMMBase model;
	static Corpus corpus;
	public static int lastIter = -1;
	public static HashMap<String, String> wordToCluster;
	public static HashMap<String, HashSet<String>> clusterToWords;
	public static void main(String[] args) throws IOException {
		//populateBrownInfo();
		corpus = new Corpus("\\s+", Config.vocabThreshold);
		//loadFromIndependentHMM("out/model/brown_baumwelch_10states");
		//loadFromDifferentLayerHMM(nrLayers);
		
		lastIter = LastIter.read();
		if(lastIter < 0) {
			initializeNewModel();
			//loadFromDifferentLayerHMM(3);
		} else {
			load();
			//checkTestPerplexity();
		}
		Config.printParams();
		readData();
		train();
		saveModel();
		test();
	}
	
	public static void readData() throws IOException {
		corpus.readTrain(Config.baseDirData + Config.trainFile);
		if(Config.testFile != null && !Config.testFile.equals("")) {
			corpus.readTest(Config.baseDirData + Config.testFile);
		}
		if(Config.devFile != null && !Config.devFile.equals("")) {
			corpus.readDev(Config.baseDirData + Config.devFile);
		}
	}
	
	public static void train() throws FileNotFoundException {
		EM em = new EM(Config.numIter, corpus, model);
		em.start();		
	}
	
	public static void saveModel() {
		model.saveModel();
	}
	
	public static void test() {
		if(Corpus.testInstanceList != null) {
			testVariational(model, Corpus.testInstanceList, Config.outFileTest);
		} else {
			testVariational(model, Corpus.trainInstanceList, Config.outFileTrain);
		}
	}
	
	public static void initializeNewModel() throws IOException {
		corpus.readVocab(Config.baseDirData + Config.vocabFile);
		Corpus.corpusVocab.get(0).writeDictionary(Config.baseDirModel + "vocab.txt");
		corpus.setupSampler();
		model = new HMMNoFinalStateLog(Config.nrLayers, Config.numStates, corpus);
		corpus.model = model;
		//random init
		model.initializeRandom(Config.random);
		model.initializeZerosToBest();		
	}
	
	public static void loadFromIndependentHMM(String folder) throws IOException {
		model = new HMMNoFinalStateLog(Config.nrLayers, Config.numStates, corpus);
		model.loadModelsFromIndependentHMM(folder);
		corpus.model = model;
		model.initializeZerosToBest();		
	}

	public static void load() throws IOException {
		model = new HMMNoFinalStateLog(Config.nrLayers, Config.numStates, corpus);
		String filename = "variational_model_layers_" + Config.nrLayers + 
				"_states_" + Config.numStates + 
				"_iter_" + lastIter + ".txt";
		model.loadModel(filename);		
		corpus.model = model;
		model.initializeZerosToBest();
	}
	
	public static void loadFromDifferentLayerHMM(int nrLayers) throws IOException {
		model = new HMMNoFinalStateLog(Config.nrLayers, Config.numStates, corpus);
		
		/*** load other model ***/
		HMMBase otherModel = new HMMNoFinalStateLog(nrLayers, Config.numStates, corpus);
		String filename = "variational_model_layers_" + nrLayers + 
				"_states_" + Config.numStates + 
				//"_iter_" + lastIter + ".txt";
				"_final.txt";
		otherModel.loadModel(filename);
		model.initializeRandom(Config.random);
		model.param.cloneFromDifferentLayerModel(otherModel.param);
		otherModel = null;
		/*** other model complete ***/
		
		corpus.model = model;
		model.initializeZerosToBest();
	}
	
	public static void checkTestPerplexity() {
		HMMPowModel powModel = new HMMPowModel(model);
		System.out.println("Power model created");
		if(Corpus.testInstanceList != null) {
			double varLL = 0.0;
			for(int n=0; n<Corpus.testInstanceList.size(); n++) {
				if(n % 1000 == 0) {
					System.out.print(n + " ");
				}
				varLL += Corpus.testInstanceList.get(n).getVariationalLL(powModel);
			}
			System.out.println();
			varLL = varLL / Corpus.testInstanceList.numberOfTokens;
			double perplexity = Math.pow(2, -varLL/Math.log(2));
			System.out.println("varLL = " + varLL + " perplexity = " + perplexity);
		} else {
			throw new RuntimeException("Test instances not loaded for checking perplexity");
		}
		 
	}

	public static void testVariational(HMMBase model, InstanceList instanceList, String outFile) {
		System.out.println("Decoding variational");
		Timing decodeTiming = new Timing();
		decodeTiming.start();
		System.out.println("Decoding started on :" + new Date().toString());
		model.param.expWeights = model.param.weights.getCloneExp();
		InstanceList.featurePartitionCache = new ConcurrentHashMap<String, Double>();
		Config.variationalIter = 20;
		instanceList.doVariationalInference(model);
		try{
			PrintWriter pw = new PrintWriter(Config.baseDirDecode + outFile);
			//viterbi decoded states
			for (int n = 0; n < instanceList.size(); n++) {
				Instance i = instanceList.get(n);
				i.decode();
				for (int t = 0; t < i.T; t++) {
					String word = i.getWord(t);
					StringBuffer sb = new StringBuffer();
					sb.append(word + " ");
					for(int m=0; m<model.nrLayers; m++) {
						int state = i.decodedStates[m][t];
						sb.append("|" + state);
					}
					pw.println(sb.toString());
					pw.flush();
				}
				pw.println();				
			}
			pw.close();
			//posterior expectations
			PrintWriter pwPosterior = new PrintWriter(Config.baseDirDecode + outFile + ".posterior");
			for (int n = 0; n < instanceList.size(); n++) {
				Instance i = instanceList.get(n);
				for (int t = 0; t < i.T; t++) {
					String word = i.getWord(t);
					StringBuffer sb = new StringBuffer();
					sb.append(word + " ");
					for(int m=0; m<model.nrLayers; m++) {
						for(int state=0; state<model.nrStates; state++) {
							sb.append("|" + i.posteriors[m][t][state]);
						}
					}
					pw.println(sb.toString());
					pw.flush();
				}
				pw.println();
				i.clearInference();
			}
			pwPosterior.close();
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		model.param.expWeights = null;
		InstanceList.featurePartitionCache = null;
		System.out.println("Finished decoding");
		System.out.println("Total decoding time : " + decodeTiming.stop());
	}
	
	public static void populateBrownInfo() throws IOException {
		String file = "brown-brown-cluster.txt";
		wordToCluster = new HashMap<String, String>();
		clusterToWords = new HashMap<String, HashSet<String>>();
		BufferedReader br = new BufferedReader(new FileReader(file));
		String line;
		while( (line = br.readLine()) != null) {
			String[] splitted = line.trim().split("\\s+");
			String cluster = splitted[0];
			String word = splitted[1];
			wordToCluster.put(word, cluster);
			if(! clusterToWords.containsKey(cluster)) {
				HashSet<String> temp = new HashSet<String>();
				temp.add(word);
				clusterToWords.put(cluster, temp);
			} else {
				clusterToWords.get(cluster).add(word);
			}
		}
		//view one cluster
		for( String word : clusterToWords.get(wordToCluster.get("million"))) {
			System.out.print(word + " ");
		}
		System.out.println("Total clusters = " + clusterToWords.size());
		br.close();
	}
}
