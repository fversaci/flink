/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.graph.generator;

import org.apache.commons.math3.random.RandomGenerator;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.Graph;
import org.apache.flink.graph.Vertex;
import org.apache.flink.graph.generator.random.BlockInfo;
import org.apache.flink.graph.generator.random.RandomGenerableFactory;
import org.apache.flink.types.LongValue;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;

import java.util.List;

/*
 * @see <a href="http://www.cs.cmu.edu/~christos/PUBLICATIONS/siam04.pdf">R-MAT: A Recursive Model for Graph Mining</a>
 */
public class RMatGraph<T extends RandomGenerator>
extends AbstractGraphGenerator<LongValue, NullValue, NullValue> {

	// Default RMat constants
	public static final float DEFAULT_A = 0.57f;

	public static final float DEFAULT_B = 0.19f;

	public static final float DEFAULT_C = 0.19f;

	public static final float DEFAULT_NOISE = 0.10f;

	// Required to create the DataSource
	private ExecutionEnvironment env;

	// Required configuration
	private final RandomGenerableFactory<T> randomGenerableFactory;

	private final long vertexCount;

	private final long edgeCount;

	// Optional configuration
	private float A = DEFAULT_A;

	private float B = DEFAULT_B;

	private float C = DEFAULT_C;

	private boolean noiseEnabled = false;

	private float noise = DEFAULT_NOISE;

	/**
	 * Generate a directed or undirected power-law {@link Graph} using the
	 * Recursive Matrix (R-Mat) model.
	 *
	 * @param env the Flink execution environment
	 * @param randomGeneratorFactory source of randomness
	 * @param vertexCount number of vertices
	 * @param edgeCount number of edges
	 */
	public RMatGraph(ExecutionEnvironment env, RandomGenerableFactory<T> randomGeneratorFactory, long vertexCount, long edgeCount) {
		if (vertexCount <= 0) {
			throw new IllegalArgumentException("Vertex count must be greater than zero");
		}

		if (edgeCount <= 0) {
			throw new IllegalArgumentException("Edge count must be greater than zero");
		}

		this.env = env;
		this.randomGenerableFactory = randomGeneratorFactory;
		this.vertexCount = vertexCount;
		this.edgeCount = edgeCount;
	}

	/**
	 * The parameters for recursively subdividing the adjacency matrix.
	 *
	 * Setting A = B = C = 0.25 emulates the Erdős–Rényi model.
	 *
	 * Graph500 uses A = 0.57, B = C = 0.19.
	 *
	 * @param A likelihood of source bit = 0, target bit = 0
	 * @param B likelihood of source bit = 0, target bit = 1
	 * @param C likelihood of source bit = 1, target bit = 0
	 * @return this
	 */
	public RMatGraph<T> setConstants(float A, float B, float C) {
		if (A < 0.0f || B < 0.0f || C < 0.0f || A + B + C > 1.0f) {
			throw new RuntimeException("RMat parameters A, B, and C must be non-negative and sum to less than or equal to one");
		}

		this.A = A;
		this.B = B;
		this.C = C;

		return this;
	}

	/**
	 * Enable and configure noise. Each edge is generated independently, but
	 * when noise is enabled the parameters A, B, and C are randomly increased
	 * or decreased, then normalized, by a fraction of the noise factor during
	 * the computation of each bit.
	 *
	 * @param noiseEnabled whether to enable noise perturbation
	 * @param noise strength of noise perturbation
	 * @return this
	 */
	public RMatGraph<T> setNoise(boolean noiseEnabled, float noise) {
		if (noise < 0.0f || noise > 2.0f) {
			throw new RuntimeException("RMat parameter noise must be non-negative and less than or equal to 2.0");
		}

		this.noiseEnabled = noiseEnabled;
		this.noise = noise;

		return this;
	}

	@Override
	public Graph<LongValue,NullValue,NullValue> generate() {
		int scale = Long.SIZE - Long.numberOfLeadingZeros(vertexCount - 1);

		// Edges
		int cyclesPerEdge = noiseEnabled ? 5 * scale : scale;

		List<BlockInfo<T>> generatorBlocks = randomGenerableFactory
			.getRandomGenerables(edgeCount, cyclesPerEdge);

		DataSet<Edge<LongValue,NullValue>> edges = env
			.fromCollection(generatorBlocks)
				.name("Random generators")
			.rebalance()
				.setParallelism(parallelism)
				.name("Rebalance")
			.flatMap(new GenerateEdges<T>(vertexCount, scale, A, B, C, noiseEnabled, noise))
				.setParallelism(parallelism)
				.name("RMat graph edges");

		// Vertices
		DataSet<Vertex<LongValue,NullValue>> vertices = GraphGeneratorUtils.vertexSet(edges, parallelism);

		// Graph
		return Graph.fromDataSet(vertices, edges, env);
	}

	private static final class GenerateEdges<T extends RandomGenerator>
	implements FlatMapFunction<BlockInfo<T>, Edge<LongValue,NullValue>> {

		// Configuration
		private final long vertexCount;

		private final int scale;

		private final float A;

		private final float B;

		private final float C;

		private final float D;

		private final boolean noiseEnabled;

		private final float noise;

		// Output
		private LongValue source = new LongValue();

		private LongValue target = new LongValue();

		private Edge<LongValue,NullValue> sourceToTarget = new Edge<>(source, target, NullValue.getInstance());

		private Edge<LongValue,NullValue> targetToSource = new Edge<>(target, source, NullValue.getInstance());

		public GenerateEdges(long vertexCount, int scale, float A, float B, float C, boolean noiseEnabled, float noise) {
			this.vertexCount = vertexCount;
			this.scale = scale;
			this.A = A;
			this.B = B;
			this.C = C;
			this.D = 1.0f - A - B - C;
			this.noiseEnabled = noiseEnabled;
			this.noise = noise;
		}

		@Override
		public void flatMap(BlockInfo<T> blockInfo, Collector<Edge<LongValue,NullValue>> out)
				throws Exception {
			RandomGenerator rng = blockInfo.getRandomGenerable().generator();
			long edgesToGenerate = blockInfo.getElementCount();

			while (edgesToGenerate > 0) {
				long x = 0;
				long y = 0;

				// matrix constants are reset for each edge
				float a = A;
				float b = B;
				float c = C;
				float d = D;

				for (int bit = 0; bit < scale; bit++) {
					// generated next bit for source and target
					x <<= 1;
					y <<= 1;

					float random = rng.nextFloat();

					if (random <= a) {
					} else if (random <= a + b) {
						y += 1;
					} else if (random <= a + b + c) {
						x += 1;
					} else {
						x += 1;
						y += 1;
					}

					if (noiseEnabled) {
						// noise is bounded such that all parameters remain non-negative
						a *= 1.0 - noise / 2 + rng.nextFloat() * noise;
						b *= 1.0 - noise / 2 + rng.nextFloat() * noise;
						c *= 1.0 - noise / 2 + rng.nextFloat() * noise;
						d *= 1.0 - noise / 2 + rng.nextFloat() * noise;

						// normalize back to a + b + c + d = 1.0
						float norm = 1.0f / (a + b + c + d);

						a *= norm;
						b *= norm;
						c *= norm;

						// could multiply by norm, but subtract to minimize rounding error
						d = 1.0f - a - b - c;
					}
				}

				// if vertexCount is not a power-of-2 then discard edges outside the vertex range
				if (x < vertexCount && y < vertexCount) {
					source.setValue(x);
					target.setValue(y);

					out.collect(sourceToTarget);

					edgesToGenerate--;
				}
			}
		}
	}
}
