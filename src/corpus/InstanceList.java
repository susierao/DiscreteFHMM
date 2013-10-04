package corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import config.Config;
import model.HMMBase;
import model.inference.VariationalParam;
import model.param.HMMParamBase;
import model.param.LogLinearWeights;
import util.MathUtils;
import util.Timing;

public class InstanceList extends ArrayList<Instance> {
	/*
	public static double shiL1NormAll=0;
	public static double alphaL1NormAll=0;
	*/
	public static double expectationL1NormAll=0;
	public static double expectationL1NormMax=0;
	
	//locks used for threads
	private static final Object gradientLock = new Object();
	private static final Object gradientLockSoft = new Object();
	private static final Object cllLock = new Object();
	private static final Object cllLockSoft = new Object();
	private static final Object variationalLock = new Object();
	
	public double LL = 0;
	double cll = 0;
	double gradient[][];
	
	private static final long serialVersionUID = -2409272084529539276L;
	public int numberOfTokens;	
	
	public InstanceList() {
		super();		
	}
	
	static public Map<String, Double> featurePartitionCache;

	/*
	 * just to get the LL of the data
	 */
	public double getLL(HMMBase model) {
		throw new UnsupportedOperationException("Not yet implemented");				
	}
	
	public double getJointLL(Instance instance, HMMBase model) {
		double jointLL = 0;
		//for t=0;
		for(int l=0; l<model.nrLayers; l++) {
			jointLL += model.param.initial.get(l).get(instance.decodedStates[l][0], 0);			
		}
		jointLL += instance.getObservationProbabilityUsingLLModel(0);
		
		for(int t=1; t<instance.T; t++) {
			for(int l=0; l<model.nrLayers; l++) {
				jointLL += model.param.transition.get(l).get(instance.decodedStates[l][t], instance.decodedStates[l][t-1]);
			}
			jointLL += instance.getObservationProbabilityUsingLLModel(t);
		}
		return jointLL;
	}
	
	/*
	 * called by the E-step of EM. 
	 * Does inference, computes posteriors and updates expected counts
	 * returns LL of the corpus
	 */
	public double updateExpectedCounts(HMMBase model, HMMParamBase expectedCounts) {
		//cache expWeights for the model
		model.param.expWeights = model.param.weights.getCloneExp();
		
		featurePartitionCache = new ConcurrentHashMap<String, Double>();
		doVariationalInference(model);
		
		//decode the most likely states and compute joint likelihood to return
		double jointLL = 0;
		LL = 0;
		for (int n = 0; n < this.size(); n++) {
			Instance instance = this.get(n);
			instance.doInference(model);
			LL += instance.logLikelihood;
			for(int l=0; l<model.nrLayers; l++) {
				instance.forwardBackwardList.get(l).addToCounts(expectedCounts);
			}
			instance.decode();
			jointLL += getJointLL(instance, model);
			//instance.clearInference();
			instance.varParam = null;
		}
		//clear expWeights;				
		model.param.expWeights = null;
		featurePartitionCache = null;
		System.out.println("LL = " + (LL/numberOfTokens));
		return jointLL;
	}
	
	public void clearPosteriorProbabilities() {
		for (int n = 0; n < this.size(); n++) {
			Instance instance = this.get(n);
			instance.posteriors = null;
		}
	}
	
	public void clearDecodedStates() {
		for (int n = 0; n < this.size(); n++) {
			Instance instance = this.get(n);
			instance.decodedStates = null;
		}
	}
	
	public void doVariationalInference(HMMBase model) {
		//optimize variational parameters
		for(int iter=0; iter < Config.variationalIter; iter++) {
			LL = 0;
			expectationL1NormAll = 0;
			expectationL1NormMax = 0;
			Timing varIterTime = new Timing();
			varIterTime.start();
			
			//start parallel processing
			int divideSize = this.size() / Config.USE_THREAD_COUNT;
			List<VariationalWorker> threadList = new ArrayList<VariationalWorker>();
			int startIndex = 0;
			int endIndex = divideSize;		
			for(int i=0; i<Config.USE_THREAD_COUNT; i++) {
				VariationalWorker worker = new VariationalWorker(this, startIndex, endIndex, model);
				threadList.add(worker);
				worker.start();
				startIndex = endIndex;
				endIndex = endIndex + divideSize;			
			}
			//there might be some remaining
			VariationalWorker finalWorker = new VariationalWorker(this, startIndex, this.size(), model);
			finalWorker.start();
			threadList.add(finalWorker);
			//start all threads and wait for them to complete
			for(VariationalWorker worker : threadList) {
				try {
					worker.join();
				} catch (InterruptedException e) {				
					e.printStackTrace();
				}
				updateVariationalComputation(worker);
			}
			StringBuffer updateString = new StringBuffer();
			updateString.append("\tvar iter=" + iter);
			expectationL1NormAll = expectationL1NormAll/this.numberOfTokens/model.nrLayers/model.nrStates;
			LL = LL/this.numberOfTokens;
			updateString.append(String.format(" LL=%.3f time=%s", LL, varIterTime.stop()));
			updateString.append(String.format(" l1avg=%f", expectationL1NormAll));
			updateString.append(String.format(" max=%f", expectationL1NormMax));
			System.out.println(updateString.toString());
			
			if(expectationL1NormMax < 1e-2) {
				System.out.println("variational params converged");
				break;
			}
			
		}		
	}
	
	public void updateVariationalComputation(VariationalWorker worker) {
        synchronized (variationalLock) {
        	expectationL1NormAll += worker.localExpectationL1Norm;
			LL += worker.localLL;
			if(worker.localExpectationL1Max > expectationL1NormMax) {
				expectationL1NormMax = worker.localExpectationL1Max; 
			}
        }
    }
	
	
	
	private class VariationalWorker extends Thread{
		double localExpectationL1Norm = 0;
		double localExpectationL1Max = 0;
		double localLL = 0;
		InstanceList instanceList;
		final int startIndex;
		final int endIndex;
		final HMMBase model;
		
		// [startIndex, endIndex) i.e. last index is not included
		public VariationalWorker(InstanceList instanceList, int startIndex, int endIndex, HMMBase model) {
			this.instanceList = instanceList;
			this.startIndex = startIndex;
			this.endIndex = endIndex;
			this.model = model;
		}
		
		@Override
		public void run() {
			localExpectationL1Norm = 0.0;
			localExpectationL1Max = 0.0;
			//instance level params
			for (int n = startIndex; n < endIndex; n++) {
				Instance instance = instanceList.get(n);
				instance.model = model;
				if(instance.varParam == null) {
					instance.varParam = new VariationalParam(model, instance);
					instance.doInference(model); //get expected states with unoptimized params
				}
				instance.varParam.optimize();
				instance.posteriorDifference = 0;
				instance.posteriorDifferenceMax = 0;
				instance.doInference(model);
				instance.decode();
				//localLL += getJointLL(instance, model);
				localLL += instance.logLikelihood;
				localExpectationL1Norm += instance.posteriorDifference;
				if(instance.posteriorDifferenceMax > localExpectationL1Max) {
					localExpectationL1Max = instance.posteriorDifferenceMax; 
				}
				
			}
		}		
	}
	
	public void decode(HMMBase model) {
		//decode
		for (int n = 0; n < this.size(); n++) {
			Instance instance = this.get(n);
			instance.model = model;
			instance.decode();							
		}		
	}
	
	public double getCLL(double[][] parameterMatrix) {
		
		return getCLLSoft(parameterMatrix);
		
	}
	
	//returns the lowerbound value
	private double getCLLSoft(double[][] parameterMatrix) {
		Timing t = new Timing();
		double cll = 0;
		double[][] expWeights = MathUtils.expArray(parameterMatrix);
		for (int n = 0; n < this.size(); n++) {
			Instance i = get(n);
			cll += i.getConditionalLogLikelihoodSoft(parameterMatrix, expWeights);
		}
		if(Config.displayDetail) {
			System.out.println("CLL computation time : " + t.stop());
		}
		return cll;
	}
	
	private double getCLLNoThread(double[][] parameterMatrix) {
		featurePartitionCache = new ConcurrentHashMap<String, Double>();
		double cll = 0;
		Timing timing = new Timing();
		timing.start();
		double[][] expWeights = MathUtils.expArray(parameterMatrix);
		
		for (int n = 0; n < this.size(); n++) {
			Instance i = get(n);
			cll += i.getConditionalLogLikelihoodUsingViterbi(expWeights);
		}
		if(Config.displayDetail) {
			System.out.println("CLL computation time : " + timing.stop());
		}
		featurePartitionCache = null;
		return cll;
		
	}
	
	private double getCLLThreaded(double[][] parameterMatrix) {
		featurePartitionCache = new ConcurrentHashMap<String, Double>();
		cll = 0;
		Timing timing = new Timing();
		timing.start();
		double[][] expWeights = MathUtils.expArray(parameterMatrix);
		
		//start parallel processing
		int divideSize = this.size() / Config.USE_THREAD_COUNT;
		List<CllWorker> threadList = new ArrayList<CllWorker>();
		int startIndex = 0;
		int endIndex = divideSize;		
		for(int i=0; i<Config.USE_THREAD_COUNT; i++) {
			CllWorker worker = new CllWorker(this, startIndex, endIndex, expWeights);
			threadList.add(worker);
			worker.start();
			startIndex = endIndex;
			endIndex = endIndex + divideSize;			
		}
		//there might be some remaining
		CllWorker finalWorker = new CllWorker(this, startIndex, this.size(), expWeights);
		finalWorker.start();
		threadList.add(finalWorker);
		//start all threads and wait for them to complete
		for(CllWorker worker : threadList) {
			try {
				worker.join();
			} catch (InterruptedException e) {				
				e.printStackTrace();
			}
			updateCLLComputation(worker);
		}
		if(Config.displayDetail) {
			System.out.println("CLL computation time : " + timing.stop());
		}
		featurePartitionCache = null;
		return cll;
	}
	
	private void updateCLLComputation(CllWorker worker) {
	    synchronized (cllLock) {
	    	cll += worker.result;
        }
    }
	
	private class CllWorker extends Thread{
		public double result;
		
		InstanceList instanceList;
		final int startIndex;
		final int endIndex;
		final double[][] expWeights;		
		
		// [startIndex, endIndex) i.e. last index is not included
		public CllWorker(InstanceList instanceList, int startIndex, int endIndex, double[][] expWeights) {
			this.instanceList = instanceList;
			this.startIndex = startIndex;
			this.endIndex = endIndex;
			this.expWeights = expWeights;
		}
		
		@Override
		public void run() {
			result = 0.0;
			for(int n=startIndex; n<endIndex; n++) {
				Instance instance = instanceList.get(n);
				//result += instance.getConditionalLogLikelihoodUsingViterbi(expWeights);
				result += instance.getConditionalLogLikelihoodUsingViterbi(expWeights);
			}
		}		
	}

	public double[][] getGradient(double[][] parameterMatrix) {
		return getGradientSoft(parameterMatrix);
		//return getGradientSoftNaive(parameterMatrix);
		
	}
	
	private double[][] getGradientNoThread(double[][] parameterMatrix) {
		Timing timing = new Timing();
		double[][] expParam = MathUtils.expArray(parameterMatrix);
		timing.start();
		int vocabSize = Corpus.corpusVocab.get(0).vocabSize;
		
		double gradient[][] = new double[parameterMatrix.length][parameterMatrix[0].length];
		long totalPartitionTime = 0;
		long totalGradientTime = 0;
		long totalConditionalTime = 0;
		for (int n = 0; n < this.size(); n++) {
			Instance instance = get(n);
			for (int t = 0; t < instance.T; t++) {
				Timing timing2 = new Timing();
				timing2.start();
				double[] conditionalVector = instance.getConditionalVector(t);
				totalConditionalTime += timing2.stopGetLong();
				//create partition
				timing2.start();
				for(int j=0; j<expParam[0].length; j++) {
					if(conditionalVector[j] != 0) {
						gradient[instance.words[t][0]][j] += 1; 
					}
				}
			}
		}
		
		for(FrequentConditionalStringVector f : Corpus.frequentConditionals) {
			double[] conditionalVector = f.vector;
			double partition = 0.0;
			double[] numeratorArray = new double[vocabSize];
			//fill the arrays
			for(int v=0; v<vocabSize; v++) {
				double numerator = MathUtils.expDot(expParam[v], conditionalVector);
				numeratorArray[v] = numerator;
				partition += numerator;
			}
			for(int j=0; j<expParam[0].length; j++) {
				if(conditionalVector[j] != 0) {
					for(int v=0; v<expParam.length; v++) {
						gradient[v][j] -= f.count * numeratorArray[v]/ partition;
					}
				}
			}										
		} 
		if(Config.displayDetail) {
			System.out.println("Total conditional time : " + totalConditionalTime);
			System.out.println("Total partition time : " + totalPartitionTime);
			System.out.println("Total gradient update time : " + totalGradientTime);
			System.out.println("Gradient computation time : " + timing.stop());
		}
		return gradient;
	}
	
	private double[][] getGradientThreaded(double[][] parameterMatrix) {		
		Timing timing = new Timing();
		double[][] expParam = MathUtils.expArray(parameterMatrix);
		timing.start();
		//cache for frequent conditonals
		int vocabSize = Corpus.corpusVocab.get(0).vocabSize;
		gradient = new double[parameterMatrix.length][parameterMatrix[0].length];
		for (int n = 0; n < this.size(); n++) {
			Instance instance = get(n);
			for (int t = 0; t < instance.T; t++) {
				double[] conditionalVector = instance.getConditionalVector(t);
				//String conditionalString = instance.getConditionalString(t);
				//if(Corpus.frequentConditionals.contains(conditionalString)) {
				//positive
				for(int j=0; j<expParam[0].length; j++) {
					if(conditionalVector[j] != 0) {
						gradient[instance.words[t][0]][j] += 1; 
					}
				}
				//}
			}
		}
		
		//start parallel processing based on frequent conditionals
		int divideSize = Corpus.frequentConditionals.size() / Config.USE_THREAD_COUNT;
		List<GradientWorker> threadList = new ArrayList<GradientWorker>();
		int startIndex = 0;
		int endIndex = divideSize;		
		for(int i=0; i<Config.USE_THREAD_COUNT; i++) {
			GradientWorker worker = new GradientWorker(Corpus.frequentConditionals, startIndex, endIndex, expParam);
			threadList.add(worker);
			worker.start();
			startIndex = endIndex;
			endIndex = endIndex + divideSize;			
		}
		//there might be some remaining
		GradientWorker finalWorker = new GradientWorker(Corpus.frequentConditionals, startIndex, Corpus.frequentConditionals.size(), expParam);
		finalWorker.start();
		threadList.add(finalWorker);
		//start all threads and wait for them to complete
		for(GradientWorker worker : threadList) {
			try {
				worker.join();
			} catch (InterruptedException e) {				
				e.printStackTrace();
			}
			updateGradientComputation(worker);
		}
		if(Config.displayDetail) {
			System.out.println("Gradient computation time : " + timing.stop());
		}
		return gradient;
	}
	
	private void updateGradientComputation(GradientWorker worker) {
        synchronized (gradientLock) {
        	MathUtils.addMatrix(gradient, worker.gradientLocal);
        }
    }
	
	private class GradientWorker extends Thread{
		public double[][] gradientLocal;
		final int startIndex;
		final int endIndex;
		final double[][] expParam;
		ArrayList<FrequentConditionalStringVector> freqConditionals;
		
		// [startIndex, endIndex) i.e. last index is not included
		public GradientWorker(ArrayList<FrequentConditionalStringVector> freqConditionals, int startIndex, int endIndex, double[][] expParam) {
			this.startIndex = startIndex;
			this.endIndex = endIndex;
			this.expParam = expParam;
			this.freqConditionals = freqConditionals;
		}
		
		@Override
		public void run() {
			gradientLocal = new double[expParam.length][expParam[0].length];
			for(int n=startIndex; n<endIndex; n++) {
				FrequentConditionalStringVector conditional = freqConditionals.get(n);
				double[] conditionalVector = conditional.vector;
				double partition = 0.0;
				double[] numeratorArray = new double[Corpus.corpusVocab.get(0).vocabSize];
				for(int v=0; v<Corpus.corpusVocab.get(0).vocabSize; v++) {
					double numerator = MathUtils.expDot(expParam[v], conditionalVector);				
					numeratorArray[v] = numerator;
					partition += numerator;
				}
				for(int j=0; j<expParam[0].length; j++) {
					if(conditionalVector[j] != 0) {
						for(int v=0; v<expParam.length; v++) {
							gradientLocal[v][j] -= conditional.count * numeratorArray[v]/ partition;
						}
					}
				}
			}
		}		
	}
	
	private double[][] getGradientSoft(double[][] parameterMatrix) {
		Timing timing = new Timing();
		timing.start();
		int vocabSize = Corpus.corpusVocab.get(0).vocabSize;
		double[][] expParam = MathUtils.expArray(parameterMatrix);
		double gradient[][] = new double[parameterMatrix.length][parameterMatrix[0].length];		
		for(int n=0; n<this.size(); n++) {
			Instance instance = get(n);
			for(int t=0; t<instance.T; t++) {
				for(int m=0; m<Config.nrLayers; m++) {
					for(int k=0; k<Config.numStates; k++) {
						gradient[instance.words[t][0]][LogLinearWeights.getIndex(m, k)] += instance.posteriors[m][t][k];						 
					}
				}
				for(int y=0; y<vocabSize; y++) {
					double dotProdOverAllLayers = 1.0; //to reduce complexity from O(m^2) to O(m)
					for(int m=0; m<Config.nrLayers; m++) {
						double dot = 0;
						for(int k=0; k<Config.numStates; k++) {
							dot += instance.posteriors[m][t][k] * expParam[y][LogLinearWeights.getIndex(m, k)];
						}
						dotProdOverAllLayers *= dot;
						MathUtils.check(dotProdOverAllLayers);
						if(dotProdOverAllLayers == 0) {
							throw new RuntimeException("underflow");
						}
					}
					//set them now
					for(int m=0; m<Config.nrLayers; m++) {
						double mLayerDot = 0.0;
						for(int l=0; l<Config.numStates; l++) {
							mLayerDot += instance.posteriors[m][t][l] * expParam[y][LogLinearWeights.getIndex(m, l)];
						}
						for(int k=0; k<Config.numStates; k++) {
							//compute the amount that must be multiplied to adjust from dotProdOverAllLayers
							double factorDifference = instance.posteriors[m][t][k] * expParam[y][LogLinearWeights.getIndex(m, k)] / mLayerDot;
							gradient[y][LogLinearWeights.getIndex(m, k)] -= dotProdOverAllLayers * factorDifference;
						}
					}
				}
				
			}
		}
		if(Config.displayDetail) {
			System.out.println("Gradient computation time : " + timing.stop());
		}
		return gradient;
	}
	
	//implemented according to the derivation in the paper
	private double[][] getGradientSoftNaive(double[][] parameterMatrix) {
		Timing timing = new Timing();
		timing.start();
		int vocabSize = Corpus.corpusVocab.get(0).vocabSize;
		double[][] expParam = MathUtils.expArray(parameterMatrix);
		double gradient[][] = new double[parameterMatrix.length][parameterMatrix[0].length];
		
		for(int m=0; m<Config.nrLayers; m++) {
			for(int y=0; y<vocabSize; y++) {
				for(int k=0; k<Config.numStates; k++) {
					for(int n=0; n<this.size(); n++) {
						Instance instance = get(n);
						for(int t=0; t<instance.T; t++) {
							if(instance.words[t][0] == y) {
								gradient[y][LogLinearWeights.getIndex(m, k)] += instance.posteriors[m][t][k];
							}
							double prod = 1.0;
							for(int p=0; p<Config.nrLayers; p++){
								if(p == m) {
									prod *= instance.posteriors[p][t][k] * expParam[y][LogLinearWeights.getIndex(p, k)];
								} else {
									double dot = 0.0;
									for(int l=0; l<Config.numStates; l++) {
										dot += instance.posteriors[p][t][l] * expParam[y][LogLinearWeights.getIndex(p, l)];
									}
									prod *= dot;
								}
								MathUtils.check(prod);
								if(prod == 0) {
									throw new RuntimeException("underflow");
								}
							}
							gradient[y][LogLinearWeights.getIndex(m, k)] -= prod;
						}
					}	
				}
			}
		}
		if(Config.displayDetail) {
			System.out.println("Gradient computation time : " + timing.stop());
		}
		return gradient;
	}
}
