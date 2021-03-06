package model.inference;

import model.HMMBase;
import util.MathUtils;
import corpus.Corpus;
import corpus.Instance;

public class VariationalParam {
	public VariationalParamObservation varParamObs;
	public VariationalParamAlpha alpha;
	public HMMBase model;
	public Instance instance;
	
	int M;
	int K;
	int V;
	int T;
	
	//for a fixed t, cache for each Y the dot product (S_tn, exp(theta_nY)) for all layers n 
	double prodCache[];
	
	
	public VariationalParam(HMMBase model, Instance instance) {
		this.model = model;
		M = model.nrLayers;
		K = model.nrStates;
		V = Corpus.corpusVocab.get(0).vocabSize;
		this.T = instance.T;
		this.instance = instance;
		
		varParamObs = new VariationalParamObservation(M, T, K);
		//varParamObs.initializeRandom();
		varParamObs.initializeFromObsParam(model.param, instance);
		alpha = new VariationalParamAlpha(T);
		//alpha.initializeRandom();		
		alpha.initializeUniform(1.0);
	}
	
	public void optimize() {
		for(int t=0; t<instance.T; t++) {
			if(M > 1) {
				//createCache(t);
				createCacheLogFix(t);
				optimizeAlpha(t);
			}
			//optimizeParamObs(t);
			optimizeParamObsNew(t);
			clearCache();
		}
	}
	
	public void createCache(int t) {
		prodCache = new double[V];
		for(int y=0; y<V; y++) {
			double prod = 1.0;
			//TODO: might underflow
			for(int n=0; n<M; n++) {
				prod = prod * MathUtils.dot(model.param.expWeights.getStateVector(n, y), 
						instance.forwardBackwardList.get(n).posterior[t]);
				MathUtils.check(prod);
				if(prod == 0) {
					throw new RuntimeException("Underflow");
				}			
			}
			prodCache[y] = prod;
		}
	}
	
	public void createCacheLogFix(int t) {
		prodCache = new double[V];
		for(int y=0; y<V; y++) {
			double prodLog = 0.0;
			for(int n=0; n<M; n++) {
				double dot = MathUtils.dot(model.param.expWeights.getStateVector(n, y), 
						instance.forwardBackwardList.get(n).posterior[t]);
				if(dot == 0) {
					dot = Math.log(1e-200);
				} else {
					dot = Math.log(dot);
				}
				prodLog = prodLog + dot;
			}
			prodCache[y] = Math.exp(prodLog);
		}
	}
	
	public void clearCache() {
		prodCache = null;
	}
	
	
	public void optimizeParamObsNew(int t) {
		if(M > 1) {
			for(int m=0; m<M; m++) {
				double[] sumOverY = new double[K];
				
				for(int y=0; y<V; y++) {
					double allProd = prodCache[y];
					double prod = allProd / MathUtils.dot(model.param.expWeights.getStateVector(m, y), 
							instance.forwardBackwardList.get(m).posterior[t]); //all prod except m'th layer
					for(int k=0; k<K; k++) {
						sumOverY[k] += prod * model.param.expWeights.getStateVector(m, y)[k];
					}
				}
				
				double normalizer = 0;
				double maxOverK = -Double.MAX_VALUE;
				double[] updateValue = new double[K];
				for(int k=0; k<K; k++) {
					double prod = alpha.alpha[t] * sumOverY[k];
					updateValue[k] = model.param.weights.get(m, k, instance.words[t][0]) - prod;
					if(updateValue[k] > maxOverK) {
						maxOverK = updateValue[k];
					}
				}
				normalizer = MathUtils.logsumexp(updateValue);
				//normalize and update				
				for(int k=0; k<K; k++) {
					//varParamObs.shi[m][t][k] = updateValue[k] - maxOverK;
					varParamObs.shi[m][t][k] = updateValue[k] - normalizer;
					MathUtils.check(varParamObs.shi[m][t][k]);
				}
			}
		} else {
			for(int m=0; m<M; m++) {
				double normalizer = 0;
				double[] updateValue = new double[K];
				for(int k=0; k<K; k++) {
					updateValue[k] = model.param.weights.get(m, k, instance.words[t][0]);
				}
			}
		}
	}
	
	public void optimizeAlpha(int t) {
		double sumY = 0;
		for(int y=0; y<V; y++) {
			double prodM = prodCache[y];
			sumY += prodM;
		}	
		if(sumY <= 0) {
			System.err.println(String.format("WARNING: sumY = %f, setting small value", sumY));
			sumY = 1e-300;
		}
		alpha.alpha[t] = 1/sumY;
		if(alpha.alpha[t] == 0) {
			System.err.println("WARNING: alpha is zero");
			alpha.alpha[t] = 1e-300;
		}
		MathUtils.check(alpha.alpha[t]);		
	}
	
	/*
	public void optimizeParamObs(int t) {
		double minExtreme = Double.MAX_VALUE;
		double maxExtreme = -Double.MAX_VALUE;
		//optimize shi's
		double shiL1NormInstance = 0;
			for(int m=0; m<M; m++) {
				double[] sumOverNYt = new double[K];
				for(int k=0; k<K; k++) {
					for(int n=0; n<M; n++) {				
						sumOverNYt[k] += model.param.weights.get(n, k, instance.words[t][0]);
					}
				}
				
				double[] sumOverNnotM = new double[K];
				for(int k=0; k<K; k++) {
					for(int n=0; n<M; n++) {
						if(n != m) {
							sumOverNnotM[k] += varParamObs.shi[n][t][k];
						}
					}
				}
				
				double[] sumOverY = new double[K];
				for(int y=0; y<V; y++) {
					double allProd = prodCache[y];
					double prod = allProd / MathUtils.dot(model.param.expWeights.getStateVector(m, y), 
							instance.forwardBackwardList.get(m).posterior[t]); //all prod except m'th layer
					for(int k=0; k<K; k++) {
						sumOverY[k] += prod * model.param.expWeights.getStateVector(m, y)[k];
					}
				}
				double normalizer = 0;
				double maxOverK = -Double.MAX_VALUE;
				double[] updateValue = new double[K];
				for(int k=0; k<K; k++) {
					updateValue[k] = sumOverNYt[k] - sumOverNnotM[k] - alpha.alpha[t] * sumOverY[k];
					if(updateValue[k] > maxOverK) {
						maxOverK = updateValue[k];
					}
				}
				normalizer = MathUtils.logsumexp(updateValue);
				//System.out.println("Normalizer : " + normalizer);
				//System.out.println("MaxoverK " + maxOverK);
				//normalize and update				
				for(int k=0; k<K; k++) {
					double oldValue = varParamObs.shi[m][t][k];
					//varParamObs.shi[m][t][k] = updateValue[k] - maxOverK;
					varParamObs.shi[m][t][k] = updateValue[k] - normalizer;
					MathUtils.check(varParamObs.shi[m][t][k]);
					shiL1NormInstance += Math.abs(oldValue - varParamObs.shi[m][t][k]);					
				}
				//instance.forwardBackwardList.get(m).doInference();
			}
//		InstanceList.shiL1NormAll += shiL1NormInstance;		
	}
	*/
}
