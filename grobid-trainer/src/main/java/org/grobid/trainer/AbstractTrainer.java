package org.grobid.trainer;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.grobid.core.GrobidModel;
import org.grobid.core.engines.tagging.GenericTagger;
import org.grobid.core.engines.tagging.GrobidCRFEngine;
import org.grobid.core.engines.tagging.TaggerFactory;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.factory.GrobidFactory;
import org.grobid.core.utilities.GrobidProperties;
import org.grobid.core.utilities.TextUtilities;
import org.grobid.trainer.evaluation.EvaluationUtilities;
import org.grobid.trainer.evaluation.ModelStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * @author Zholudev, Lopez
 */
public abstract class AbstractTrainer implements Trainer {
    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractTrainer.class);
    public static final String OLD_MODEL_EXT = ".old";
    public static final String NEW_MODEL_EXT = ".new";

    // default training parameters (only exploited by Wapiti)
    protected double epsilon = 0.0; // size of the interval for stopping criterion
    protected int window = 0; // similar to CRF++
    protected int nbMaxIterations = 0; // maximum number of iterations in training

    protected GrobidModel model;
    private File trainDataPath;
    private File evalDataPath;
    private GenericTagger tagger;

    public AbstractTrainer(final GrobidModel model) {
        GrobidFactory.getInstance().createEngine();
        this.model = model;
        this.trainDataPath = getTempTrainingDataPath();
        this.evalDataPath = getTempEvaluationDataPath();
    }

    public void setParams(double epsilon, int window, int nbMaxIterations) {
        this.epsilon = epsilon;
        this.window = window;
        this.nbMaxIterations = nbMaxIterations;
    }

    @Override
    public int createCRFPPData(final File corpusDir, final File trainingOutputPath) {
        return createCRFPPData(corpusDir, trainingOutputPath, null, 1.0);
    }

    @Override
    public void train() {
        final File dataPath = trainDataPath;
        createCRFPPData(getCorpusPath(), dataPath);
        GenericTrainer trainer = TrainerFactory.getTrainer();

        if (epsilon != 0.0)
            trainer.setEpsilon(epsilon);
        if (window != 0)
            trainer.setWindow(window);
        if (nbMaxIterations != 0)
            trainer.setNbMaxIterations(nbMaxIterations);

        File dirModelPath = new File(GrobidProperties.getModelPath(model).getAbsolutePath()).getParentFile();
        if (!dirModelPath.exists()) {
            LOGGER.warn("Cannot find the destination directory " + dirModelPath.getAbsolutePath() + " for the model " + model.getModelName() + ". Creating it.");
            dirModelPath.mkdir();
            //throw new GrobidException("Cannot find the destination directory " + dirModelPath.getAbsolutePath() + " for the model " + model.toString());
        }
        final File tempModelPath = new File(GrobidProperties.getModelPath(model).getAbsolutePath() + NEW_MODEL_EXT);
        final File oldModelPath = GrobidProperties.getModelPath(model);
        trainer.train(getTemplatePath(), dataPath, tempModelPath, GrobidProperties.getNBThreads(), model);
        // if we are here, that means that training succeeded
        // rename model for CRF sequence labellers (not with DeLFT deep learning models)
        if (GrobidProperties.getGrobidCRFEngine() != GrobidCRFEngine.DELFT)
            renameModels(oldModelPath, tempModelPath);
    }

    protected void renameModels(final File oldModelPath, final File tempModelPath) {
        if (oldModelPath.exists()) {
            if (!oldModelPath.renameTo(new File(oldModelPath.getAbsolutePath() + OLD_MODEL_EXT))) {
                LOGGER.warn("Unable to rename old model file: " + oldModelPath.getAbsolutePath());
                return;
            }
        }

        if (!tempModelPath.renameTo(oldModelPath)) {
            LOGGER.warn("Unable to rename new model file: " + tempModelPath);
        }
    }

    @Override
    public String evaluate() {
        createCRFPPData(getEvalCorpusPath(), evalDataPath);
        return EvaluationUtilities.reportMetrics(EvaluationUtilities.evaluateStandard(evalDataPath.getAbsolutePath(), getTagger()));
    }

    @Override
    public String splitTrainEvaluate(Double split) {
        final File dataPath = trainDataPath;
        createCRFPPData(getCorpusPath(), dataPath, evalDataPath, split);
        GenericTrainer trainer = TrainerFactory.getTrainer();

        if (epsilon != 0.0)
            trainer.setEpsilon(epsilon);
        if (window != 0)
            trainer.setWindow(window);
        if (nbMaxIterations != 0)
            trainer.setNbMaxIterations(nbMaxIterations);

        File dirModelPath = new File(GrobidProperties.getModelPath(model).getAbsolutePath()).getParentFile();
        if (!dirModelPath.exists()) {
            LOGGER.warn("Cannot find the destination directory " + dirModelPath.getAbsolutePath() + " for the model " + model.getModelName() + ". Creating it.");
            dirModelPath.mkdir();
            //throw new GrobidException("Cannot find the destination directory " + dirModelPath.getAbsolutePath() + " for the model " + model.toString());
        }

        final File tempModelPath = new File(GrobidProperties.getModelPath(model).getAbsolutePath() + NEW_MODEL_EXT);
        final File oldModelPath = GrobidProperties.getModelPath(model);

        trainer.train(getTemplatePath(), dataPath, tempModelPath, GrobidProperties.getNBThreads(), model);

        // if we are here, that means that training succeeded
        renameModels(oldModelPath, tempModelPath);

        return EvaluationUtilities.reportMetrics(EvaluationUtilities.evaluateStandard(evalDataPath.getAbsolutePath(), getTagger()));
    }

    @Override
    public String nFoldEvaluate(int folds) {
        final File dataPath = trainDataPath;
        createCRFPPData(getCorpusPath(), dataPath);
        GenericTrainer trainer = TrainerFactory.getTrainer();

        // Load in memory and Shuffle
        Path dataPath2 = Paths.get(dataPath.getAbsolutePath());
        List<String> trainingData = loadAndShuffle(dataPath2);

        // Split into folds
        List<ImmutablePair<String, String>> foldMap = splitNFold(trainingData, folds);

        // Train and evaluation
        if (epsilon != 0.0)
            trainer.setEpsilon(epsilon);
        if (window != 0)
            trainer.setWindow(window);
        if (nbMaxIterations != 0)
            trainer.setNbMaxIterations(nbMaxIterations);

        //We dump the model in the tmp directory
        File tmpDirectory = new File(GrobidProperties.getTempPath().getAbsolutePath());
        if (!tmpDirectory.exists()) {
            LOGGER.warn("Cannot find the destination directory " + tmpDirectory);
        }

        final File tempModelPath = new File(tmpDirectory + File.separator + "nfold_dummy_model");
        System.out.println("Saving model in " + tempModelPath);

        List<ModelStats> evaluationResults = foldMap.stream().map(fold -> {
            trainer.train(getTemplatePath(), new File(fold.getLeft()), tempModelPath, GrobidProperties.getNBThreads(), model);
            return EvaluationUtilities.evaluateStandard(fold.getRight(), getTagger());
        }).collect(Collectors.toList());

        System.out.println("Results: ");


        Comparator<ModelStats> f1ScoreComparator = (o1, o2) -> {
            if (o1.getFieldStats().getMacroAverageF1() > o1.getFieldStats().getMacroAverageF1()) {
                return 1;
            } else if (o1.getFieldStats().getMacroAverageF1() < o1.getFieldStats().getMacroAverageF1()) {
                return -1;
            } else {
                return 0;
            }
        };
        // Output
        StringBuilder sb = new StringBuilder();
        Optional<ModelStats> worstModel = evaluationResults.stream().min(f1ScoreComparator);
        sb.append("Worst Model").append("\n");
        ModelStats worstModelStats = worstModel.orElseGet(() -> {
            throw new GrobidException("Something wrong when computing evaluations " +
                "- worst model metrics not found. ");
        });
        sb.append(EvaluationUtilities.reportMetrics(worstModelStats));

        Optional<ModelStats> bestModel = evaluationResults.stream().max(f1ScoreComparator);
        ModelStats bestModelStats = bestModel.orElseGet(() -> {
            throw new GrobidException("Something wrong when computing evaluations " +
                "- best model metrics not found. ");
        });
        sb.append(EvaluationUtilities.reportMetrics(bestModelStats));

        // Averages
        OptionalDouble averageF1 = evaluationResults.stream().mapToDouble(e -> e.getFieldStats().getMacroAverageF1()).average();
        OptionalDouble averagePrecision = evaluationResults.stream().mapToDouble(e -> e.getFieldStats().getMacroAveragePrecision()).average();
        OptionalDouble averageRecall = evaluationResults.stream().mapToDouble(e -> e.getFieldStats().getMacroAverageRecall()).average();

        double avgPrecision = averagePrecision.orElseGet(() -> {
            throw new GrobidException("Missing average precision. Something went wrong. Please check. ");
        });
        sb.append("Average precision: " + TextUtilities.formatTwoDecimals(avgPrecision * 100)).append("\n");

        double avgRecall = averageRecall.orElseGet(() -> {
            throw new GrobidException("Missing average recall. Something went wrong. Please check. ");
        });
        sb.append("Average recall: " + TextUtilities.formatTwoDecimals(avgRecall * 100)).append("\n");

        double avgF1 = averageF1.orElseGet(() -> {
            throw new GrobidException("Missing average F1. Something went wrong. Please check. ");
        });
        sb.append("Average F1: " + TextUtilities.formatTwoDecimals(avgF1 * 100)).append("\n");

        return sb.toString();
    }

    /**
     * Partition the corpus in n folds, dump them in n files and return the pairs of (trainingPath, evaluationPath)
     */
    protected List<ImmutablePair<String, String>> splitNFold(List<String> trainingData, int folds) {
        int trainingSize = CollectionUtils.size(trainingData);
        int foldSize = Math.floorDiv(trainingSize, folds);

        return IntStream.range(0, folds).mapToObj(foldIndex -> {
            int foldStart = foldSize * foldIndex;
            int foldEnd = foldStart + foldSize;

            if (foldIndex == folds - 1) {
                foldEnd = trainingSize;
            }

            List<String> foldEvaluation = trainingData.subList(foldStart, foldEnd);
            List<String> foldTraining0 = trainingData.subList(0, foldStart);
            List<String> foldTraining1 = trainingData.subList(foldEnd, trainingSize);
            List<String> foldTraining = new ArrayList<>();
            foldTraining.addAll(foldTraining0);
            foldTraining.addAll(foldTraining1);

            //Dump Evaluation
            String tempEvaluationDataPath = getTempEvaluationDataPath().getAbsolutePath();
//            System.out.println(tempEvaluationDataPath);
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(tempEvaluationDataPath))) {
//                System.out.println(String.join("\n\n\n", foldEvaluation));
                writer.write(String.join("\n\n", foldEvaluation));
            } catch (IOException e) {
                e.printStackTrace();
            }

            //Dump Training
            String tempTrainingDataPath = getTempTrainingDataPath().getAbsolutePath();
//            System.out.println(tempTrainingDataPath);
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(tempTrainingDataPath))) {
//                System.out.println(String.join("\n\n\n", foldTraining));
                writer.write(String.join("\n\n", foldTraining));
            } catch (IOException e) {
                e.printStackTrace();
            }

            return new ImmutablePair<>(tempTrainingDataPath, tempEvaluationDataPath);
        }).collect(Collectors.toList());
    }

    /**
     * Load the dataset in memory and shuffle it. Assuming that each empty line is a delimiter between instances.
     * Empty line are filtered out from the output
     */
    protected List<String> loadAndShuffle(Path dataPath) {
        List<String> trainingData = new ArrayList<>();
        try (Stream<String> stream = Files.lines(dataPath)) {
            List<String> instance = new ArrayList<>();
            ListIterator<String> iterator = stream.collect(Collectors.toList()).listIterator();
            while (iterator.hasNext()) {
                String current = iterator.next();

                if (StringUtils.isBlank(current)) {
                    if (CollectionUtils.isNotEmpty(instance)) {
                        trainingData.add(String.join("\n", instance));
                    }
                    instance = new ArrayList<>();
                } else {
                    instance.add(current);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }

        Collections.shuffle(trainingData);
        return trainingData;
    }

    protected final File getTempTrainingDataPath() {
        try {
            return File.createTempFile(model.getModelName(), ".train", GrobidProperties.getTempPath());
        } catch (IOException e) {
            throw new RuntimeException("Unable to create a temporary training file for model: " + model);
        }
    }

    protected final File getTempEvaluationDataPath() {
        try {
            return File.createTempFile(model.getModelName(), ".test", GrobidProperties.getTempPath());
        } catch (IOException e) {
            throw new RuntimeException("Unable to create a temporary evaluation file for model: " + model);
        }
    }

    protected GenericTagger getTagger() {
        if (tagger == null) {
            tagger = TaggerFactory.getTagger(model);
        }

        return tagger;
    }

    protected static File getFilePath2Resources() {
        File theFile = new File(GrobidProperties.get_GROBID_HOME_PATH().getAbsoluteFile() + File.separator + ".." + File.separator
            + "grobid-trainer" + File.separator + "resources");
        if (!theFile.exists()) {
            theFile = new File("resources");
        }
        return theFile;
    }

    protected File getCorpusPath() {
        return GrobidProperties.getCorpusPath(getFilePath2Resources(), model);
    }

    protected File getTemplatePath() {
        return getTemplatePath(model);
    }

    protected File getTemplatePath(final GrobidModel model) {
        return GrobidProperties.getTemplatePath(getFilePath2Resources(), model);
    }

    protected File getEvalCorpusPath() {
        return GrobidProperties.getEvalCorpusPath(getFilePath2Resources(), model);
    }

    public static File getEvalCorpusBasePath() {
        final String path2Evelutation = getFilePath2Resources().getAbsolutePath() + File.separator + "dataset" + File.separator + "patent"
            + File.separator + "evaluation";
        return new File(path2Evelutation);
    }

    @Override
    public GrobidModel getModel() {
        return model;
    }

    public static void runTraining(final Trainer trainer) {
        long start = System.currentTimeMillis();
        trainer.train();
        long end = System.currentTimeMillis();

        System.out.println("Model for " + trainer.getModel() + " created in " + (end - start) + " ms");
    }

    public File getEvalDataPath() {
        return evalDataPath;
    }

    public static void runEvaluation(final Trainer trainer) {
        long start = System.currentTimeMillis();
        try {
            String report = trainer.evaluate();
            System.out.println(report);
        } catch (Exception e) {
            throw new GrobidException("An exception occurred while evaluating Grobid.", e);
        }
        long end = System.currentTimeMillis();
        System.out.println("Evaluation for " + trainer.getModel() + " model is realized in " + (end - start) + " ms");
    }

    public static void runSplitTrainingEvaluation(final Trainer trainer, Double split) {
        long start = System.currentTimeMillis();
        try {
            String report = trainer.splitTrainEvaluate(split);
            System.out.println(report);
        } catch (Exception e) {
            throw new GrobidException("An exception occurred while evaluating Grobid.", e);
        }
        long end = System.currentTimeMillis();
        System.out.println("Split, training and evaluation for " + trainer.getModel() + " model is realized in " + (end - start) + " ms");
    }


    public static void runNFoldEvaluation(final Trainer trainer, int numFolds) {
        long start = System.currentTimeMillis();
        try {
            String report = trainer.nFoldEvaluate(numFolds);
            System.out.println(report);
        } catch (Exception e) {
            throw new GrobidException("An exception occurred while evaluating Grobid.", e);
        }
        long end = System.currentTimeMillis();
        System.out.println("Split, training and evaluation for " + trainer.getModel() + " model is realized in " + (end - start) + " ms");
    }

    /**
     * Dispatch the example to the training or test data, based on the split ration and the drawing of
     * a random number
     */
    public Writer dispatchExample(Writer writerTraining, Writer writerEvaluation, double splitRatio) {
        Writer writer = null;
        if ((writerTraining == null) && (writerEvaluation != null)) {
            writer = writerEvaluation;
        } else if ((writerTraining != null) && (writerEvaluation == null)) {
            writer = writerTraining;
        } else {
            if (Math.random() <= splitRatio)
                writer = writerTraining;
            else
                writer = writerEvaluation;
        }
        return writer;
    }


}
