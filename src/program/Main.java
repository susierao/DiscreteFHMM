package program;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import util.MathUtils;
import util.Timing;
import model.HMMBase;
import model.HMMNoFinalStateLog;
import model.inference.Decoder;
import model.train.EM;
import corpus.Corpus;
import corpus.Instance;
import corpus.InstanceList;

public class Main {
	public static Random random = new Random();

	public final static int USE_THREAD_COUNT = 8;

	/** user parameters **/
	static String delimiter = "\\+";
	static int numIter;
	public static long seed = 4321;

	static String trainFile;
	static String vocabFile;
	static String testFile;
	static String devFile;
	
	static String outFolderPrefix;
	static HMMBase model;
	static Corpus corpus;
	public static int sampleSizeEStep = 250;
	public static int sampleSizeMStep = 250;

	static int oneTimeStepObsSize; // number of elements in observation e.g.
									// word|hmm1|hmm2 has 3

	static int vocabThreshold = 1; // only above this included*******
	public static int nrLayers = 6;
	public static int numStates = 10;

	/** user parameters end **/
	public static void main(String[] args) throws IOException {
		train();
		// String testFilenameBase = "out/decoded/test.txt.SPL";
		// decodeFromPlainText(testFilenameBase, recursionSize);
	}

	public static void train() throws IOException {
		InstanceList.VOCAB_UPDATE_COUNT = 0;
		outFolderPrefix = "out/";
		numIter = 50;
		String trainFileBase;
		String testFileBase;
		String devFileBase;
		
		//trainFileBase = "data/simple_corpus_sorted.txt";
		trainFileBase = "data/combined.txt.SPL";
		testFileBase = "data/test.txt.SPL";
		devFileBase = "data/srl.txt";
		trainFile = trainFileBase;
		testFile = testFileBase;
		devFile = devFileBase;
		vocabFile = trainFile;
		String outFileTrain = trainFileBase + ".decoded";
		String outFileTest = testFileBase + ".decoded";
		String outFileDev = devFileBase + ".decoded";
		
		corpus = new Corpus("\\s+", vocabThreshold);
		Corpus.oneTimeStepObsSize = Corpus.findOneTimeStepObsSize(vocabFile);
		// TRAIN
		corpus.readVocab(vocabFile);
		corpus.corpusVocab.get(0).writeDictionary("out/model/vocab.txt");
		
		if(InstanceList.VOCAB_UPDATE_COUNT <= 0) {
			InstanceList.VOCAB_UPDATE_COUNT = Corpus.corpusVocab.get(0).vocabSize;
		}
		// corpus.setupSampler();
		corpus.readTrain(trainFile);
		//corpus.readTest(testFile);
		//corpus.readDev(devFile);
		model = new HMMNoFinalStateLog(nrLayers, numStates, corpus);
		corpus.model = model;
		Random random = new Random(seed);
		
		//random init
		
		model.initializeRandom(random);
		//model.param.weights.initializeZeros();
		//model.param.weights.initializeUniform(0.01);
		model.initializeZerosToBest();
		
		printParams();
		
		
		//model loading for continuing the training
		//model.loadModel();
		
		EM em = new EM(numIter, corpus, model);
		em.start();
		
		model.saveModel();
		
		
		
		/*
		if(corpus.testInstanceList != null) {
			System.out.println("LL of Test Data : " + corpus.testInstanceList.getLL(model));
			test(model, corpus.testInstanceList, outFileTest);
		}
		if(corpus.devInstanceList != null) {
			System.out.println("LL of Dev Data : " + corpus.devInstanceList.getLL(model));
			test(model, corpus.devInstanceList, outFileDev);
		}
		test(model, corpus.trainInstanceList, outFileTrain);
		*/
		
		testVariational(model, corpus.trainInstanceList, outFileTrain);
	}
	
	public static void testVariational(HMMBase model, InstanceList instanceList, String outFile) {
		System.out.println("Decoding variational");
		Timing decodeTiming = new Timing();
		decodeTiming.start();
		System.out.println("Decoding started on :" + new Date().toString());
		model.param.expWeights = model.param.weights.getCloneExp();
		model.param.expWeightsCache = MathUtils.expArray(model.param.weights.weights);
		InstanceList.featurePartitionCache = new ConcurrentHashMap<String, Double>();
		instanceList.doVariationalInference(model); //also decodes
		try{
			PrintWriter pw = new PrintWriter(outFile);
			for (int n = 0; n < instanceList.size(); n++) {
				Instance i = instanceList.get(n);
				//i.decode();
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
				i.clearInference();
			}
			pw.close();
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		model.param.expWeightsCache = null;
		model.param.expWeights = null;
		InstanceList.featurePartitionCache = null;
		System.out.println("Finished decoding");
		System.out.println("Total decoding time : " + decodeTiming.stop());
	}
	
	
	// use exact observation probability (also in forwardBackward) for decoding
	public static void test(HMMBase model, InstanceList instanceList,
			String outFile) {
		System.out.println("Decoding Data");
		Timing decodeTiming = new Timing();
		decodeTiming.start();
		System.out.println("Decoding started on :" + new Date().toString());
		model.param.expWeightsCache = MathUtils
				.expArray(model.param.weights.weights);
		InstanceList.featurePartitionCache = new ConcurrentHashMap<String, Double>();
		Decoder decoder = new Decoder(model);
		try {
			PrintWriter pw = new PrintWriter(new FileWriter(outFile));
			PrintWriter pwSimple = new PrintWriter(new FileWriter(outFile
					+ ".simple")); // word and latest hmmstate (no intermediate
									// hmm states)
			PrintWriter pwSimpleAll = new PrintWriter(new FileWriter(outFile
					+ ".simple.all")); // word and intermediate hmm states and
										// final
			for (int n = 0; n < instanceList.size(); n++) {
				Instance instance = instanceList.get(n);
				int[] decoded = decoder.viterbi(instance);
				for (int t = 0; t < decoded.length; t++) {
					String word = instance.getWord(t);
					int state = decoded[t];
					pwSimple.println(word + " " + state);
					pwSimpleAll.print(word + "\t");
					for (int k = 0; k < corpus.oneTimeStepObsSize; k++) {
						pw.print(corpus.corpusVocab.get(k).indexToWord
								.get(instance.words[t][k]));
						pw.print("|");
						if (k != 0) {
							// except the word itself
							pwSimpleAll
									.print(corpus.corpusVocab.get(k).indexToWord
											.get(instance.words[t][k]));
							pwSimpleAll.print("|");
						}
					}
					pw.print(state);
					if (t != decoded.length - 1) {
						pw.print(" ");
					}
					pwSimpleAll.print(state);
					pwSimpleAll.println();
				}
				pwSimpleAll.println();
				pwSimple.println();
				pw.println();
			}
			pwSimple.println();
			pw.close();
			pwSimple.close();
			pwSimpleAll.close();
		} catch (IOException e) {
			System.err.format("Could not open file for writing %s\n", outFile);
			e.printStackTrace();
		}
		model.param.expWeightsCache = null;
		InstanceList.featurePartitionCache = null;
		System.out.println("Finished decoding");
		System.out.println("Total decoding time : " + decodeTiming.stop());
	}

	public static void testPosteriorDistribution(HMMBase model,
			InstanceList instanceList, String outFile) {
		System.out.println("Decoding Posterior distribution");
		Decoder decoder = new Decoder(model);
		try {
			PrintWriter pw = new PrintWriter(new FileWriter(outFile));
			for (int n = 0; n < instanceList.size(); n++) {
				Instance instance = instanceList.get(n);
				double[][] decoded = decoder.posteriorDistribution(instance);
				for (int t = 0; t < decoded.length; t++) {
					String word = instance.getWord(t);
					pw.print(word + " ");
					for (int i = 0; i < decoded[t].length; i++) {
						pw.print(decoded[t][i]);
						if (i != model.nrStates) {
							pw.print(" ");
						}
					}
					pw.println();
				}
				pw.println();
			}
			pw.close();
		} catch (IOException e) {
			System.err.format("Could not open file for writing %s\n", outFile);
			e.printStackTrace();
		}
		System.out.println("Finished decoding");
	}

	public static void printParams() {
		StringBuffer sb = new StringBuffer();
		sb.append("Train file : " + trainFile);
		sb.append("\nVocab file : " + vocabFile);
		sb.append("\nTest file : " + testFile);
		sb.append("\nDev file : " + devFile);
		sb.append("\noutFolderPrefix : " + outFolderPrefix);
		sb.append("\nIterations : " + numIter);
		sb.append("\nNumStates : " + numStates);
		sb.append("\nNumLayers : " + nrLayers);
		sb.append("\nthreads : " + USE_THREAD_COUNT);
		System.out.println(sb.toString());
		if(InstanceList.VOCAB_UPDATE_COUNT <=0 ||  InstanceList.VOCAB_UPDATE_COUNT == Corpus.corpusVocab.get(0).vocabSize) {
			System.out.println("Using exact gradient for training");
		} else {
			System.out.format("Using approx gradient with %d negative evidence vocab items for training\n", InstanceList.VOCAB_UPDATE_COUNT);
		}
	}
}
