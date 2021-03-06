package model.train;

import java.io.FileNotFoundException;

import model.HMMBase;
import model.HMMType;
import model.param.HMMParamBase;
import model.param.HMMParamFinalState;
import model.param.HMMParamNoFinalState;
import model.param.HMMParamNoFinalStateLog;
import program.Main;
import util.MathUtils;
import util.MyArray;
import util.Stats;
import util.Timing;
import cc.mallet.optimize.LimitedMemoryBFGS;
import cc.mallet.optimize.OptimizationException;
import cc.mallet.optimize.Optimizer;
import config.Config;
import config.LastIter;
import corpus.Corpus;

public class EM {
	int numIter;
	Corpus c;
	HMMBase model;

	double bestOldLL = -Double.MAX_VALUE;
	double LL = 0;

	double bestOldLLDev = -Double.MAX_VALUE;
	double devLL = 0;

	HMMParamBase expectedCounts;

	int convergeCount = 0;
	int lowerCount = 0; // number of times LL could not increase from previous
						// best
	public int iterCount = 0;
	double adaptiveWeight;

	public EM(int numIter, Corpus c, HMMBase model) {
		this.numIter = numIter;
		this.c = c;
		this.model = model;
	}

	public void setAdaptiveWeight() {
		//fraction of data
		double f = 1.0 * Corpus.trainInstanceEStepSampleList.numberOfTokens / Corpus.trainInstanceList.numberOfTokens;
		if(f == 1) {
			System.out.println("fraction = 1, all dataset used");
			adaptiveWeight = 1.0;
		} else {
			//standard adaptiveWeight technique
			//adaptiveWeight = (t0 + iterCount)^(-alpha)
			adaptiveWeight = Math.pow((Config.t0 + iterCount), - Config.alpha);
		}

		/*
		 * my approach based on iterations and fraction of samples selected
		 */
		/*
		//where a= f * maxIter / (1-f), where f is fraction of data used for trainining
		double f = 1.0 * Corpus.trainInstanceEStepSampleList.numberOfTokens / Corpus.trainInstanceList.numberOfTokens;
		double a;
		if(f == 1) {
			adaptiveWeight = 1;
		} else {
			a = f * numIter / (1-f);
			adaptiveWeight = a / (a + iterCount);
		}
		*/

	}

	public void eStep() {
		expectedCounts.initializeZeros();
		System.out.format("Estep #sentences = %d, #tokens = %d\n",
				Corpus.trainInstanceEStepSampleList.size(),
				Corpus.trainInstanceEStepSampleList.numberOfTokens);
		LL = Corpus.trainInstanceEStepSampleList.updateExpectedCounts(model, expectedCounts);
		LL = LL/Corpus.trainInstanceEStepSampleList.numberOfTokens; //per token
	}

	public void mStep() {
		setAdaptiveWeight();
		System.out.println("Iter : " + iterCount + " adaptiveWeight : " + adaptiveWeight);

		System.out.format("Mstep #sentences = %d, #tokens = %d\n",
				Corpus.trainInstanceMStepSampleList.size(),
				Corpus.trainInstanceMStepSampleList.numberOfTokens);
		//Corpus.cacheFrequentConditionals();
		trainLBFGS();
		//Corpus.clearFrequentConditionals();
		model.updateFromCountsWeighted(expectedCounts, adaptiveWeight);
		//model.updateFromCounts(expectedCounts); //unweighted
		Corpus.trainInstanceEStepSampleList.clearPosteriorProbabilities();
		Corpus.trainInstanceEStepSampleList.clearDecodedStates();
		
		//update the L1Diff norms
		model.updateL1Diff();
	}

	public void trainLBFGS() {
		// maximize CLL of the data
		double[] initParams = MyArray.createVector(model.param.weights.weights);
		CLLTrainer optimizable = new CLLTrainer(initParams, c);
		Optimizer optimizer = new LimitedMemoryBFGS(optimizable);
		boolean converged = false;
		//optimizable.checkGradientComputation();
		try {
			converged = optimizer.optimize(Config.mStepIter);
		} catch (IllegalArgumentException e) {
			System.out.println("optimization threw exception: IllegalArgument");
		} catch (OptimizationException oe) {
			System.out.println("optimization threw OptimizationException");
		}
		System.out.println("Converged = " + converged);
		System.out.println("Gradient call count = " + optimizable.gradientCallCount);
		double cll = optimizable.getValue();
		cll = cll / Corpus.trainInstanceMStepSampleList.numberOfTokens; //per token CLL
		System.out.println("CLL = " + cll);
		//model.param.weights.weights = optimizable.getParameterMatrix(); //unweighted
		
		//geometric mean
		/*
		model.param.weights.weights = MathUtils.weightedAverageMatrix(optimizable.getParameterMatrix(),
				model.param.weights.weights,
				adaptiveWeight);
		*/
		//arithmetic mean
		//model.param.weights.weightsOld = MyArray.getCloneOfMatrix(model.param.weights.weights);
		model.param.weights.weightsOld = model.param.weights.weights;
		model.param.weights.weights = MathUtils.weightedAverageofLog(optimizable.getParameterMatrix(),
				model.param.weights.weights,
				adaptiveWeight);
	}

	public void start() throws FileNotFoundException {
		if (model.hmmType == HMMType.WITH_NO_FINAL_STATE) {
			expectedCounts = new HMMParamNoFinalState(model);
		} else if (model.hmmType == HMMType.WITH_FINAL_STATE) {
			expectedCounts = new HMMParamFinalState(model);
		} else if (model.hmmType == HMMType.LOG_SCALE) {
			expectedCounts = new HMMParamNoFinalStateLog(model);
		}
		System.out.println("Starting EM");
		Timing totalEMTime = new Timing();
		totalEMTime.start();
		Timing eStepTime = new Timing();
		Timing mStepTime = new Timing();
		Timing oneIterEmTime = new Timing();
		for (iterCount = Main.lastIter + 1; iterCount < numIter; iterCount++) {
			//sample new train instances
			c.generateRandomTrainingEStepSample(Config.sampleSizeEStep, iterCount);
			LL = 0;
			// e-step
			eStepTime.start();
			oneIterEmTime.start();
			eStep();
			System.out.println("E-step time: " + eStepTime.stop());
			double trainPerplexityJoint = Math.pow(2, -LL/Math.log(2));
			System.out.println("Train perplexity : " + trainPerplexityJoint);

			double diff = LL - bestOldLL;
			// m-step
			c.generateRandomTrainingMStepSample(Config.sampleSizeMStep);
			mStepTime.start();
			mStep();
			System.out.println("InitialMaxDiff = " + model.param.l1DiffInitialMax
					+ " TransitionMaxDiff = " + model.param.l1DiffTransitionMax
					+ " WeightMaxDiff = " + model.param.l1DiffWeightsMax);
			
			System.out.println("M-step time:" + mStepTime.stop());
			Stats.totalFixes = 0;
			StringBuffer display = new StringBuffer();
			if(Corpus.devInstanceList != null && iterCount % Config.convergenceIterInterval == 0) {
				c.generateRandomDevSample(Config.sampleDevSize);
				System.out.println(String.format("Dev #sentence=%d, #tokens=%d", Corpus.devInstanceSampleList.size(), Corpus.devInstanceSampleList.numberOfTokens));
				expectedCounts.initializeZeros(); //mstep already complete
				devLL = Corpus.devInstanceSampleList.updateExpectedCounts(model, expectedCounts);
				devLL = devLL / Corpus.devInstanceSampleList.numberOfTokens;
				double devPerplexityJoint = Math.pow(2, -devLL/Math.log(2));
				double devPerplexityCLL = Math.pow(2, -Corpus.devInstanceSampleList.getCLL(model.param.weights.weights)/Math.log(2)/Corpus.devInstanceSampleList.numberOfTokens);
				double devPerplexityLL = Math.pow(2, -Corpus.devInstanceSampleList.LL /Math.log(2)/Corpus.devInstanceSampleList.numberOfTokens);
				System.out.println("Dev Perplexity LL = " + devPerplexityLL + " Joint = " + devPerplexityJoint + " CLL = " + devPerplexityCLL);
				double devDiff = devLL - bestOldLLDev;
				if(iterCount > 0) {
					display.append(String.format("DevLL %.5f devDiff %.5f ", devLL, devDiff));
				}
				if(Math.pow(model.nrStates, model.nrLayers) <= 100) {
                                        System.out.println("Checking Test Perplexity");
                                        Main.checkTestPerplexity();
                                }
				if(isConverged()) {
					break;
				}
			}
			if (iterCount > 0) {
				display.append(String.format("obj %.5f Diff %.5f \t Iter %d \t Fixes: %d \t iter time %s\n",LL, diff, iterCount,Stats.totalFixes, oneIterEmTime.stop()));
			}
			//only check if not check in dev done (because dev was null)
			if(Corpus.devInstanceList == null) {
				if (isConverged()) {
					break;
				}
			}
			if(bestOldLL < LL) {
				bestOldLL = LL;
			}
			model.saveModel(iterCount); //save every iteration
			//also save the file with the itercount
			LastIter.write(iterCount);
			
			System.out.println(display.toString());
		}
		if(Corpus.testInstanceList != null) {
			double testLL = Corpus.testInstanceList.updateExpectedCounts(model, expectedCounts);
			testLL = testLL / Corpus.testInstanceList.numberOfTokens;
			double testPerplexity = Math.pow(2, -testLL/Math.log(2));
			System.out.println("Test Perplexity : " + testPerplexity);
		}
		System.out.println("Total EM Time : " + totalEMTime.stop());
	}

	public boolean isConverged() {
		//if no dev data, use training data itself for convergence test
		if(Corpus.devInstanceList == null) {
			devLL = LL;
			bestOldLLDev = bestOldLL;
		}

		double decreaseRatio = (devLL - bestOldLLDev) / Math.abs(bestOldLLDev);
		// System.out.println("Decrease Ratio: %.5f " + decreaseRatio);
		if (Config.precision > decreaseRatio && decreaseRatio > 0) {
			convergeCount++;
			if(convergeCount > Config.maxConsecutiveConvergeLimit) {
				System.out.println("Converged. Saving the final model");
				model.saveModel();
				return true;
			}
		}
		convergeCount = 0;
		if (devLL < bestOldLLDev) {
			if (lowerCount == 0) {
				// cache the best model so far
				System.out.println("Caching the best model so far");
				if (model.bestParam != null) {
					model.bestParam.cloneFrom(model.param);
				}
			}
			lowerCount++;
			if (lowerCount == Config.maxConsecutiveDecreaseLimit) {
				System.out.format("Saying Converged: LL could not increase for %d consecutive iterations\n",
						Config.maxConsecutiveDecreaseLimit);
				if (model.bestParam != null) {
					model.param.cloneFrom(model.bestParam);
				}
				return true;
			}
			return false;
		} else {
			lowerCount = 0;
			bestOldLLDev= devLL;
			return false;
		}
	}
}
