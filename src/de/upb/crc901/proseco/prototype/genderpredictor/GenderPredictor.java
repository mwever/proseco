package de.upb.crc901.proseco.prototype.genderpredictor;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import Catalano.Imaging.FastBitmap;
import Catalano.Imaging.Texture.BinaryPattern.IBinaryPattern;
import Catalano.Imaging.Texture.BinaryPattern.LocalBinaryPattern;
import Catalano.Imaging.Tools.ImageHistogram;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.Capabilities;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;

public class GenderPredictor implements Classifier, Serializable {

	/**
	 *
	 */
	private static final long serialVersionUID = 6837682242474705246L;
	private static final String CLASSIFIER_OUT = "classifier.model";
	private static final String INSTANCES_OUT = "instances.serialized";
	private static final int ILBP_GRANULARITY = 5;

	private static IBinaryPattern bp = null;
	private Classifier c;

	public GenderPredictor() {
		super();
	}

	public GenderPredictor(final String model) {
		try (ObjectInputStream br = new ObjectInputStream(new BufferedInputStream(new FileInputStream(model)))) {
			this.c = (Classifier) br.readObject();
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	public static void main(final String[] args) {
		// TODO correct usage message
		if (args.length < 2) {
			showInvalidUsageError();
			return;
		}

		/* setup feature extractor */
		/* $featureextraction$ */;

		if (args[0].equals("-t")) {
			buildPredictor(new File(args[1]));
		} else if (args[0].equals("-q")) {
			final GenderPredictor predictor = new GenderPredictor(CLASSIFIER_OUT);
			System.out.println(predictor.getPrediction(new File(args[1])));
		} else if (args[0].equals("-i")) {
			if (args.length > 2) {
				buildInstances(new File(args[1]), Integer.parseInt(args[2]));
			} else {
				buildInstances(new File(args[1]));
			}
		} else if (args[0].equals("-acc")) {
			final GenderPredictor predictor = new GenderPredictor(CLASSIFIER_OUT);
			try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(args[1])))) {
				final Instances test = (Instances) ois.readObject();
				double accuracy = predictor.computeAccuracy(test);
				System.out.println("acc=" + accuracy);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		else {
			showInvalidUsageError();
		}
	}
	
	private static void showInvalidUsageError() {
		log("ERROR: incorrect number of arguments.");
		log("-q [pathToFile x.jpg] for prediction");
		log("-i [pathToFile data.zip] [numberOfInstancesToBuild] to build instances");
		log("-acc [pathToFile testdataInstances.serialized] to compute the accuracy for the classifier");
	}

	private static Map<String, String> getLabelMap(final Path folder) {
		Map<String, String> labels = new HashMap<>();

		try {
			final BufferedReader br = new BufferedReader(new FileReader(new File(folder.toFile().getAbsolutePath() + File.separator + "labels.txt")));
			String line;
			while ((line = br.readLine()) != null) {
				final String[] split = line.split(",");
				if (split.length == 2 && !split[1].isEmpty()) {
					labels.put(split[0], split[1]);
				}
			}
			br.close();
		} catch (final FileNotFoundException e1) {
			e1.printStackTrace();
		} catch (final IOException e) {
			e.printStackTrace();
		}

		return labels;
	}

	public static void buildInstances(final File dataFile) {
		buildInstances(dataFile, 0);
	}

	/**
	 * Draws numberOfInstances many files from the zip file. 0 means all files.
	 *
	 * @param data
	 * @param numberOfInstances
	 */
	public static void buildInstances(final File data, int numberOfInstances) {
		final Path folder = Paths.get("tmp");
		unzipPhotos(data, folder);

		/* read labels */
		final Map<String, String> labels = getLabelMap(folder);

		/* read images and assign labels */
		final Instances dataset = getEmptyDataset();

		Map<String, Integer> labelCounter = new HashMap<>();

		File[] fileArray = folder.toFile().listFiles(new FileFilter() {
			@Override
			public boolean accept(final File pathname) {
				if (!FilenameUtils.isExtension(pathname.getName(), "jpg")) {
					return false;
				}

				String label = labels.get(pathname.getName());
				if (label == null || label.equals("")) {
					return false;
				}

				if (labelCounter.containsKey(label)) {
					labelCounter.put(label, labelCounter.get(label) + 1);
				} else {
					labelCounter.put(label, 1);
				}
				return true;
			}
		});

		if (numberOfInstances <= 0) {
			numberOfInstances = fileArray.length;
		}

		int numberOfInstancesToDraw = Math.min(fileArray.length - numberOfInstances, numberOfInstances);
		Set<File> sampledFiles = new HashSet<>();
		Random r = new Random();

		while (sampledFiles.size() < numberOfInstancesToDraw) {
			int indexToAdd = r.nextInt(fileArray.length);
			sampledFiles.add(fileArray[indexToAdd]);
		}

		boolean addSampledFiles = numberOfInstancesToDraw == numberOfInstances;

		final AtomicInteger i = new AtomicInteger(0);
		Arrays.stream(fileArray).parallel().forEach(f -> {
			if ((addSampledFiles && sampledFiles.contains(f)) || (!addSampledFiles && !sampledFiles.contains(f))) {
				System.err.println("JUHUUUUU");
				processDataAndAddToDataset(f, dataset, labels.get(f.getName()));
				System.out.print("[add item " + (i.incrementAndGet()) + "]");
			}
		});

		try (ObjectOutputStream bw = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(INSTANCES_OUT)))) {
			FileUtils.deleteDirectory(folder.toFile());
			System.out.println("dataset size: " + dataset.size());
			bw.writeObject(dataset);
			System.out.println("DONE.");
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	private static void unzipPhotos(final File zipFile, final Path outputFolder) {
		final byte[] buffer = new byte[1024];
		try {
			// create output directory is not exists
			if (!outputFolder.toFile().exists()) {
				outputFolder.toFile().mkdir();
			}

			// get the zip file content
			final ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
			// get the zipped file list entry
			ZipEntry ze = zis.getNextEntry();

			while (ze != null) {
				final String fileName = ze.getName();
				final File newFile = new File(outputFolder.toFile().getAbsolutePath() + File.separator + fileName);

				// create all non exists folders
				// else you will hit FileNotFoundException for compressed folder
				new File(newFile.getParent()).mkdirs();
				final FileOutputStream fos = new FileOutputStream(newFile);
				int len;
				while ((len = zis.read(buffer)) > 0) {
					fos.write(buffer, 0, len);
				}
				fos.close();
				ze = zis.getNextEntry();
			}
			zis.closeEntry();
			zis.close();
		} catch (final IOException ex) {
			ex.printStackTrace();
		}
	}

	private double computeAccuracy(final Instances testInstances) throws Exception {
		int correctPredictions = 0;

		for (int i = 0; i < testInstances.numInstances(); i++) {
			double pred = this.classifyInstance(testInstances.instance(i));
			if (testInstances.classAttribute().value((int) testInstances.instance(i).classValue()).equals(testInstances.classAttribute().value((int) pred))) {
				correctPredictions ++;
			}
		}
		double accuracy = correctPredictions * 1f / testInstances.numInstances();
		return accuracy;
	}

	private static void buildPredictor(final File instancesFile) {
		final GenderPredictor p = new GenderPredictor();

		log("Read in instances and build classifier...");
		try (ObjectInputStream ois = new ObjectInputStream(new BufferedInputStream(new FileInputStream(instancesFile)))) {
			final Instances train = (Instances) ois.readObject();
			p.buildClassifier(train);
		} catch (final Exception e) {
			e.printStackTrace();
		}

		/* store the trained classifier in the file */
		log("Store trained classifier...");
		try (ObjectOutputStream bw = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream(CLASSIFIER_OUT)))) {
			bw.writeObject(p.c);
			System.out.println("DONE.");
		} catch (final IOException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void buildClassifier(final Instances train) throws Exception {

		/* create classifier object */
		/** ## PLACE COMPOSITION CODE HERE ## **/
		/* $classifierdef$ */

		try {
			this.c.buildClassifier(train);
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public double classifyInstance(final Instance arg0) throws Exception {
		return this.c.classifyInstance(arg0);
	}

	@Override
	public double[] distributionForInstance(final Instance arg0) throws Exception {
		return this.c.distributionForInstance(arg0);
	}

	@Override
	public Capabilities getCapabilities() {
		return this.c.getCapabilities();
	}

	public static Instances getEmptyDataset() {
		final Instances data = new Instances("images", getILBPAttributes(), 0);
		data.setClassIndex(data.numAttributes() - 1);
		return data;
	}

	private static ArrayList<Attribute> getILBPAttributes() {

		/* compute number of features */
		int numberOfFeatures;
		if (bp instanceof LocalBinaryPattern || bp instanceof Catalano.Imaging.Texture.BinaryPattern.GradientLocalBinaryPattern
				|| bp instanceof Catalano.Imaging.Texture.BinaryPattern.LocalGradientCoding || bp instanceof Catalano.Imaging.Texture.BinaryPattern.MultiblockLocalBinaryPattern) {
			numberOfFeatures = 256;
		} else if (bp instanceof Catalano.Imaging.Texture.BinaryPattern.CenterSymmetricLocalBinaryPattern) {
			numberOfFeatures = 16;
		}

		else
			numberOfFeatures = 511;

		final int n = numberOfFeatures * ILBP_GRANULARITY * ILBP_GRANULARITY; // 511 is the number of features in each square
		final ArrayList<Attribute> attributes = new ArrayList<>(n + 1);
		for (int i = 0; i < n; i++) {
			attributes.add(new Attribute("p" + i));
		}
		attributes.add(new Attribute("gender", Arrays.asList(new String[] { "male", "female" })));
		return attributes;
	}

	public String getPrediction(final File query) {
		try {

			final Instances dataset = getEmptyDataset();
			processDataAndAddToDataset(query, dataset, null);
			return Math.round(this.c.classifyInstance(dataset.firstInstance())) == 1 ? "male" : "female";
		} catch (final Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static Instance applyBP(final FastBitmap fb, final Instances dataset, final String classValue) {
		/* go through boxes and compute ilbp */

		final int[][] matrix = fb.toMatrixGrayAsInt();
		List<Integer> attributeVals = new ArrayList<>();

		/* compute ilbp histogram for each square */
		final int length = Math.min(fb.getWidth(), fb.getHeight());
		final int stepSize = (int) Math.floor(length * 1f / ILBP_GRANULARITY);
		for (int xSquare = 0; xSquare < ILBP_GRANULARITY; xSquare++) {
			for (int ySquare = 0; ySquare < ILBP_GRANULARITY; ySquare++) {

				/* determine the submatrix of this square */
				final int[][] excerpt = new int[stepSize][stepSize];
				for (int i = 0; i < stepSize; i++) {
					for (int j = 0; j < stepSize; j++) {
						excerpt[i][j] = matrix[xSquare * stepSize + i][ySquare * stepSize + j];
					}
				}

				/* create fast bitmap and apply ilbp */
				FastBitmap fb2 = new FastBitmap(excerpt);
				final ImageHistogram hist = bp.ComputeFeatures(fb2);
				final int[] attributesForSquare = hist.getValues();
				for (final int val : attributesForSquare) {
					attributeVals.add(val);
					// JOptionPane.showMessageDialog(null, fb.toIcon(), "Result", JOptionPane.PLAIN_MESSAGE);
				}
			}
		}

		/* now create instance object */
		final Instance inst = new DenseInstance(attributeVals.size() + 1);
		inst.setDataset(dataset);

		/* set attribute values */
		for (int i = 0; i < attributeVals.size(); i++)
			inst.setValue(i, attributeVals.get(i));

		/* if there is a class assigned */
		try {
			inst.setValue(attributeVals.size(), classValue);
		} catch (IllegalArgumentException e) {
			System.out.println("Class value: " + classValue);
			e.printStackTrace();
		}

		return inst;
	}

	public static void processDataAndAddToDataset(final File imageFile, final Instances dataset, final String classValue) {
		/* create matrix representation of image */
		FastBitmap fb = new FastBitmap(imageFile.getAbsolutePath());
		// Placeholder for applying image filters to the fast bitmap object
		final int min = Math.min(fb.getWidth(), fb.getHeight());
		new Catalano.Imaging.Filters.Grayscale().applyInPlace(fb);
		/* $imagefilter$ */

		Instance inst = applyBP(fb, dataset, classValue);
		dataset.add(inst);
	}

	private static void log(final String msg) {
		System.out.println("Gender Predictor: " + msg);
	}
}