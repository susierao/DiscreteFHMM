package corpus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

import model.HMMBase;
import model.inference.VariationalParam;
import model.inference.VariationalParamObservation;
import model.param.HMMParamBase;
import program.Main;
import util.MathUtils;
import util.MyArray;
import util.Timing;

public class InstanceList extends ArrayList<Instance> {
	private static final long serialVersionUID = -2409272084529539276L;
	public int numberOfTokens;
	
	public VariationalParam varParam;
	
	public static int VOCAB_UPDATE_COUNT = 1000;
	
	public InstanceList() {
		super();		
	}
	
	static public Map<String, Double> featurePartitionCache;
	static public Map<String, VocabNumeratorArray> featureNumeratorCache;

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
		doVariationalInference(model);
		
		double jointLL = 0;
		double LL = 0;
		for (int n = 0; n < this.size(); n++) {
			Instance instance = this.get(n);
			instance.doInference(model);
			LL += instance.logLikelihood;
			for(int l=0; l<model.nrLayers; l++) {
				instance.forwardBackwardList.get(l).addToCounts(expectedCounts);
			}
			instance.decode();
			jointLL += getJointLL(instance, model);
			instance.clearInference();
		}
		//clear expWeights;
		model.param.expWeightsCache = null;
		featurePartitionCache = null;
		return jointLL;
	}
	
	public void doVariationalInference(HMMBase model) {
		if(varParam == null) {
			varParam = new VariationalParam(model);
		}
		
		//cache expWeights for the model
		featurePartitionCache = new ConcurrentHashMap<String, Double>();
		model.param.expWeightsCache = MathUtils.expArray(model.param.weights.weights);
		//optimize variational parameters
		
		for(int iter=0; iter < 5; iter++) {
			double LL = 0;
			Timing varIterTime = new Timing();
			varIterTime.start();
			StringBuffer updateString = new StringBuffer();
			updateString.append("\tvar iter=" + iter);
			//instance level params
			Timing timeInstance = new Timing();
			timeInstance.start();
			VariationalParam.shiL1NormAll = 0;
			for (int n = 0; n < this.size(); n++) {
				Instance instance = this.get(n);
				if(instance.varParamObs == null) {
					instance.varParamObs = new VariationalParamObservation(model.nrLayers, instance.T, model.nrStates);
					instance.varParamObs.initializeRandom();
				}
				instance.doInference(model);
				LL += instance.logLikelihood;
				instance.model = model;
				varParam.optimizeInstanceParam(instance);
			}
			//System.out.println("shiL1Norm : " + VariationalParam.shiL1NormAll);
			//System.out.println("Instance Level optimization time: " + timeInstance.stop());
			
			//corpus level params
			Timing timeCorpus = new Timing();
			timeCorpus.start();
			varParam.optimizeCorpusParam();
			//System.out.println("Corpus level optimization time: " + timeCorpus.stop());
			updateString.append(" LL=" + LL + " time=" + varIterTime.stop());
			System.out.println(updateString.toString());
		}
	}
	/*
	 * Does not modify corpus level variational params zeta and alpha
	 */
	public void doVariationalInferenceDecoding(HMMBase model) {
		if(varParam == null) {
			varParam = new VariationalParam(model);
		}
		
		//cache expWeights for the model
		featurePartitionCache = new ConcurrentHashMap<String, Double>();
		model.param.expWeightsCache = MathUtils.expArray(model.param.weights.weights);
		//optimize variational parameters
		
		for(int iter=0; iter < 5; iter++) {
			double LL = 0;
			Timing varIterTime = new Timing();
			varIterTime.start();
			StringBuffer updateString = new StringBuffer();
			updateString.append("\tvar iter=" + iter);
			//instance level params
			Timing timeInstance = new Timing();
			timeInstance.start();
			VariationalParam.shiL1NormAll = 0;
			for (int n = 0; n < this.size(); n++) {
				Instance instance = this.get(n);
				if(instance.varParamObs == null) {
					instance.varParamObs = new VariationalParamObservation(model.nrLayers, instance.T, model.nrStates);
					instance.varParamObs.initializeRandom();
				}
				instance.doInference(model);
				LL += instance.logLikelihood;
				instance.model = model;
				varParam.optimizeInstanceParam(instance);
			}
			updateString.append(" LL=" + LL + " time=" + varIterTime.stop());
			System.out.println(updateString.toString());
		}
	}

	public double getConditionalLogLikelihoodUsingViterbi(
			double[][] parameterMatrix) {
		return getCLLNoThread(parameterMatrix);
		//return getCLLThreaded(parameterMatrix);
	}
	
	public double getCLLNoThread(double[][] parameterMatrix) {
		featurePartitionCache = new ConcurrentHashMap<String, Double>();
		double cll = 0;
		Timing timing = new Timing();
		timing.start();
		double[][] expWeights = MathUtils.expArray(parameterMatrix);
		
		for (int n = 0; n < this.size(); n++) {
			Instance i = get(n);
			cll += i.getConditionalLogLikelihoodUsingViterbi(expWeights);
		}
		//System.out.println("CLL computation time : " + timing.stop());
		featurePartitionCache = null;
		return cll;
		
	}
	
	public double getCLLThreaded(double[][] parameterMatrix) {
		featurePartitionCache = new ConcurrentHashMap<String, Double>();
		double cll = 0;
		Timing timing = new Timing();
		timing.start();
		double[][] expWeights = MathUtils.expArray(parameterMatrix);
		
		//start parallel processing
		int divideSize = this.size() / Main.USE_THREAD_COUNT;
		List<CllWorker> threadList = new ArrayList<CllWorker>();
		int startIndex = 0;
		int endIndex = divideSize;		
		for(int i=0; i<Main.USE_THREAD_COUNT; i++) {
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
			cll += worker.result;
		}
		System.out.println("CLL computation time : " + timing.stop());
		featurePartitionCache = null;
		return cll;
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
				result += instance.getConditionalLogLikelihoodUsingViterbi(expWeights);
			}
		}		
	}

	public double[][] getGradient(double[][] parameterMatrix) {
		return getGradientNoThread(parameterMatrix);
		//return getGradientThreaded(parameterMatrix);
	}
	
	public double[][] getGradientNoThread(double[][] parameterMatrix) {
		Timing timing = new Timing();
		double[][] expParam = MathUtils.expArray(parameterMatrix);
		timing.start();
		double gradient[][] = new double[parameterMatrix.length][parameterMatrix[0].length];
		long totalPartitionTime = 0;
		long totalGradientTime = 0;
		long totalConditionalTime = 0;
		long totalSortTime = 0;	
		VocabItemProbComparator comparator = new VocabItemProbComparator();
		for (int n = 0; n < this.size(); n++) {
			Instance instance = get(n);
			for (int t = 0; t < instance.T; t++) {
				Timing timing2 = new Timing();
				timing2.start();
				double[] conditionalVector = instance.getConditionalVector(t);					
				totalConditionalTime += timing2.stopGetLong();
				//create partition
				timing2.start();
					
				if(VOCAB_UPDATE_COUNT <= 0) { //exact
                    double normalizer = 0.0;
					final double[] numeratorCache = new double[parameterMatrix.length];
					
					timing2.start();
					for (int v = 0; v < parameterMatrix.length; v++) {
						double numerator = MathUtils.expDot(expParam[v], conditionalVector);
						numeratorCache[v] = numerator;
						normalizer += numerator;						
					}
					totalPartitionTime += timing2.stopGetLong();
					
					timing2.start();
					for(int j=0; j<parameterMatrix[0].length; j++) {
						if(conditionalVector[j] != 0) {
							gradient[instance.words[t][0]][j] += 1;
							for(int v=0; v<parameterMatrix.length; v++) {
								gradient[v][j] -= numeratorCache[v] / normalizer;
							}
						}
					}				
				} else {
					double normalizer = 0.0;
					// number of items in PQ will not exceed VOCAB_UPDATE_COUNT
					PriorityQueue<VocabItemProbability> topProbs = new PriorityQueue<VocabItemProbability>(VOCAB_UPDATE_COUNT, comparator);
					for (int v = 0; v < parameterMatrix.length; v++) {
						double numerator = MathUtils.expDot(expParam[v], conditionalVector);
						if(topProbs.size() < VOCAB_UPDATE_COUNT) {
							//just insert
							VocabItemProbability item = new VocabItemProbability(v, numerator);
							topProbs.add(item);
						} else {
							//find the min among the current max
							VocabItemProbability currentMinItem = topProbs.peek();
							if(numerator > currentMinItem.prob) {
								//remove the current min
								topProbs.poll();
								//insert the new one
								topProbs.add(new VocabItemProbability(v, numerator));
							}
						}
						normalizer += numerator;						
					}
					totalPartitionTime += timing2.stopGetLong();
					
					timing2.start();
					for(int j=0; j<parameterMatrix[0].length; j++) {
						if(conditionalVector[j] != 0) {
							gradient[instance.words[t][0]][j] += 1;
							for(VocabItemProbability item : topProbs) {
								gradient[item.index][j] -= item.prob / normalizer;
							}
						}
					}				
					totalGradientTime += timing2.stopGetLong();
				}				
			}
		}		
		System.out.println("Total conditional time : " + totalConditionalTime);
		System.out.println("Total partition time : " + totalPartitionTime);
		System.out.println("Total gradient update time : " + totalGradientTime);
		System.out.println("Total sort time : " + totalSortTime);
		System.out.println("Gradient computation time : " + timing.stop());		
		return gradient;
	}
	
	public double[][] getGradientThreaded(double[][] parameterMatrix) {		
		Timing timing = new Timing();
		double[][] expWeights = MathUtils.expArray(parameterMatrix);
		timing.start();
		//cache for frequent conditonals
		int vocabSize = Corpus.corpusVocab.get(0).vocabSize;
		//cache conditional numerators
		featureNumeratorCache = new HashMap<String, VocabNumeratorArray>();
		VocabItemProbComparator comparator = new VocabItemProbComparator();
		for(FrequentConditionalStringVector conditional : Corpus.frequentConditionals) {
			//initialize
			VocabNumeratorArray conditionalVocabArrays = new VocabNumeratorArray(vocabSize);
			double[] conditionalVector = conditional.vector;
			//MyArray.printVector(conditionalVector, "Conditional vector");
			double partition = 0.0;
			PriorityQueue<VocabItemProbability> topProbs = new PriorityQueue<VocabItemProbability>(VOCAB_UPDATE_COUNT, comparator);
			//fill the arrays
			for(int v=0; v<vocabSize; v++) {
				double numerator = MathUtils.expDot(expWeights[v], conditionalVector);
				if(topProbs.size() < VOCAB_UPDATE_COUNT) {
					//just insert
					VocabItemProbability item = new VocabItemProbability(v, numerator);
					topProbs.add(item);
				} else {
					//find the min among the current max
					VocabItemProbability currentMinItem = topProbs.peek();
					if(numerator > currentMinItem.prob) {
						//remove the current min
						topProbs.poll();
						//insert the new one
						topProbs.add(new VocabItemProbability(v, numerator));
					}
				}
				conditionalVocabArrays.array[v] = numerator;
				partition += numerator;
			}
			conditionalVocabArrays.normalizer = partition;
			conditionalVocabArrays.topProbs = topProbs;
			featureNumeratorCache.put(conditional.index, conditionalVocabArrays);
		}
		
		
		double gradient[][] = new double[parameterMatrix.length][parameterMatrix[0].length];
		
		//start parallel processing
		int divideSize = this.size() / Main.USE_THREAD_COUNT;
		List<GradientWorker> threadList = new ArrayList<GradientWorker>();
		int startIndex = 0;
		int endIndex = divideSize;		
		for(int i=0; i<Main.USE_THREAD_COUNT; i++) {
			GradientWorker worker = new GradientWorker(this, startIndex, endIndex, expWeights);
			threadList.add(worker);
			worker.start();
			startIndex = endIndex;
			endIndex = endIndex + divideSize;			
		}
		//there might be some remaining
		GradientWorker finalWorker = new GradientWorker(this, startIndex, this.size(), expWeights);
		finalWorker.start();
		threadList.add(finalWorker);
		//start all threads and wait for them to complete
		for(GradientWorker worker : threadList) {
			try {
				worker.join();
			} catch (InterruptedException e) {				
				e.printStackTrace();
			}
			MathUtils.addMatrix(gradient, worker.gradient);
		}
		System.out.println("Gradient computation time : " + timing.stop());
		featureNumeratorCache = null;
		return gradient;
	}
	
	private class GradientWorker extends Thread{
		public double[][] gradient;
		final int startIndex;
		final int endIndex;
		final double[][] expWeights;
		InstanceList instanceList;
		
		// [startIndex, endIndex) i.e. last index is not included
		public GradientWorker(InstanceList instanceList, int startIndex, int endIndex, double[][] expWeights) {
			this.startIndex = startIndex;
			this.endIndex = endIndex;
			this.expWeights = expWeights;
			this.instanceList = instanceList;
		}
		
		@Override
		public void run() {
			VocabItemProbComparator comparator = new VocabItemProbComparator();
			gradient = new double[expWeights.length][expWeights[0].length];
			for(int n=startIndex; n<endIndex; n++) {
				Instance instance = instanceList.get(n);
				for (int t = 0; t < instance.T; t++) {
					double[] conditionalVector = instance.getConditionalVector(t);
					String conditionalString = instance.getConditionalString(t);
					//create partition
					if(VOCAB_UPDATE_COUNT <= 0) { //exact
	                    double normalizer = 0.0;
						final double[] numeratorCache = new double[expWeights.length];
						for (int v = 0; v < expWeights.length; v++) {
							double numerator = MathUtils.expDot(expWeights[v], conditionalVector);
							numeratorCache[v] = numerator;
							normalizer += numerator;						
						}
						for(int j=0; j<expWeights[0].length; j++) {
							if(conditionalVector[j] != 0) {
								gradient[instance.words[t][0]][j] += 1;
								for(int v=0; v<expWeights.length; v++) {
									gradient[v][j] -= numeratorCache[v] / normalizer;
								}
							}
						}				
					} else {
						if(featureNumeratorCache.containsKey(conditionalString)) {
							for(int j=0; j<expWeights[0].length; j++) {
								if(conditionalVector[j] != 0) {
									gradient[instance.words[t][0]][j] += 1;
									VocabNumeratorArray vna = featureNumeratorCache.get(conditionalString);
									for(VocabItemProbability item : vna.topProbs) {
										gradient[item.index][j] -= item.prob / vna.normalizer;
									}
								}
							}
						} else {
							double normalizer = 0.0;
							PriorityQueue<VocabItemProbability> topProbs = new PriorityQueue<VocabItemProbability>(VOCAB_UPDATE_COUNT, comparator);
							for (int v = 0; v < expWeights.length; v++) {
								double numerator;
								numerator = MathUtils.expDot(expWeights[v], conditionalVector);
								if(topProbs.size() < VOCAB_UPDATE_COUNT) {
									//just insert
									VocabItemProbability item = new VocabItemProbability(v, numerator);
									topProbs.add(item);
								} else {
									//find the min among the current max
									VocabItemProbability currentMinItem = topProbs.peek();
									if(numerator > currentMinItem.prob) {
										//remove the current min
										topProbs.poll();
										//insert the new one
										topProbs.add(new VocabItemProbability(v, numerator));
									}
								}									
								normalizer += numerator;
							}
							for(int j=0; j<expWeights[0].length; j++) {
								if(conditionalVector[j] != 0) {
									gradient[instance.words[t][0]][j] += 1;
									for(VocabItemProbability item : topProbs) {
										gradient[item.index][j] -= item.prob / normalizer;
									}
								}
							}
						}
					}
				}
			}
		}		
	}
	
	/*
	 * old code for the gradient (exactly based on derivation)
	 */
	/*
		public double[][] getGradient(double[][] parameterMatrix) {
			Timing timing = new Timing();
			timing.start();
			//TODO: can further speed up partitionCache calculation (because for different state in the same timestep, Z's remain fixed)  
			double[][] partitionCache = new double[this.numberOfTokens][this.get(0).model.nrStates];
			int tokenIndex = 0;
			for(int n=0; n<this.size(); n++) {
				Instance instance = get(n);
				for(int t=0; t<instance.T; t++) {
					for(int state=0; state < instance.model.nrStates; state++) {
						double[] conditionalVector = instance.getConditionalVector(t, state);
						double normalizer = 0.0;
						for (int v = 0; v < parameterMatrix.length; v++) {
							double[] weightVector = parameterMatrix[v];
							normalizer += Math.exp(MathUtils.dot(weightVector, conditionalVector));
						}
						partitionCache[tokenIndex][state] = normalizer;
					}
					tokenIndex++;
				}
			}
			
			System.out.println("Partition Cache creation time : " + timing.stop());
			
			timing.start();
			double gradient[][] = new double[parameterMatrix.length][parameterMatrix[0].length];
			for (int i = 0; i < parameterMatrix.length; i++) { // all vocab length
				for (int j = 0; j < parameterMatrix[0].length; j++) {
					tokenIndex = 0;
					for (int n = 0; n < this.size(); n++) {
						Instance instance = get(n);
						for (int t = 0; t < instance.T; t++) {
							for (int state = 0; state < instance.model.nrStates; state++) {
								double posteriorProb = instance.posteriors[t][state];
								double[] conditionalVector = instance.getConditionalVector(t, state);
								if (i == instance.words[t][0]) {
									gradient[i][j] += posteriorProb * conditionalVector[j];
								}
								double normalizer = partitionCache[tokenIndex][state];									
								double numerator = Math.exp(MathUtils.dot(parameterMatrix[i], conditionalVector));
								gradient[i][j] -= posteriorProb * numerator / normalizer * conditionalVector[j];							
							}
							tokenIndex++;						
						}
					}
				}
			}
			System.out.println("Gradient computation time : " + timing.stop());		
			return gradient;
		}
	*/
}
