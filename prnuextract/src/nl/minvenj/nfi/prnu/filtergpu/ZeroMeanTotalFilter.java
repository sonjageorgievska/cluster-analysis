/*
* Copyright 2015 Netherlands eScience Center, VU University Amsterdam, and Netherlands Forensic Institute
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package nl.minvenj.nfi.prnu.filtergpu;

import nl.minvenj.nfi.prnu.Util;
import nl.minvenj.nfi.cuba.cudaapi.*;
import jcuda.*;
import jcuda.driver.*;

/**
 * Class that applies a Zero Mean Total filter for filtering PRNU patterns
 * 
 * @author Ben van Werkhoven <b.vanwerkhoven@esciencecenter.nl>
 * @version 0.1
 *
 */
public class ZeroMeanTotalFilter {

	protected CudaContext _context;
	protected CudaStream _stream;

	//handles to CUDA kernels
	protected CudaFunction _computeMeanVertically;
	protected CudaFunction _transpose;

	//handles to device memory arrays
	protected CudaMemFloat _d_input;
	protected CudaMemFloat _d_output;

	//parameterlists for kernel invocations
	protected Pointer computeMeanVerticallyCol;
	protected Pointer transposeForward;
	protected Pointer computeMeanVerticallyRow;
	protected Pointer transposeBackward;
	
	protected int h;
	protected int w;

	/**
	 * Constructor for the Zero Mean Total Filter, used only by the PRNUFilter factory
	 * 
	 * @param h - the image height in pixels
	 * @param w - the image width in pixels
	 * @param context - the CudaContext as created by the factory
	 * @param stream - the CudaStream as created by the factory
	 * @param input - the GPU memory for storing the pattern
	 * @param module - the CudaModule containing the kernels compiled by the factory
	 */
	public ZeroMeanTotalFilter (int h, int w, CudaContext context, CudaStream stream, CudaMemFloat input, CudaModule module) {
		_context = context;
		_stream = stream;
		_d_input = input;
		this.h = h;
		this.w = w;

		// Setup cuda functions
		_computeMeanVertically = module.getFunction("computeMeanVertically");
		final int ZMThreads_x = 32;
		final int ZMThreads_y = 16;
		_computeMeanVertically.setDim((int)Math.ceil((float)w / (float)ZMThreads_x), 1, 1,
				ZMThreads_x, ZMThreads_y, 1);
		_transpose = module.getFunction("transpose");
		final int TThreads_x = 16;
		final int TThreads_y = 16;
		_transpose.setDim(	(int)Math.ceil((double)w/(double)TThreads_x), (int)Math.ceil((double)h/(double)TThreads_y), 1,
				TThreads_x, TThreads_y, 1);

		// Allocate the CUDA buffers for this kernel
		_d_output = _context.allocFloats(w*h);

		// Setup the parameter lists for each kernel call 
		computeMeanVerticallyCol = Pointer.to(
				Pointer.to(new int[]{h}),
				Pointer.to(new int[]{w}),
				Pointer.to(_d_input.getDevicePointer())
				);

		transposeForward = Pointer.to(
				Pointer.to(new int[]{h}),
				Pointer.to(new int[]{w}),
				Pointer.to(_d_output.getDevicePointer()),
				Pointer.to(_d_input.getDevicePointer())
				);

		computeMeanVerticallyRow = Pointer.to(
				Pointer.to(new int[]{w}),
				Pointer.to(new int[]{h}),
				Pointer.to(_d_output.getDevicePointer())
				);

		transposeBackward = Pointer.to(
				Pointer.to(new int[]{w}),
				Pointer.to(new int[]{h}),
				Pointer.to(_d_input.getDevicePointer()),
				Pointer.to(_d_output.getDevicePointer())
				);

	}

	/**
	 * Applies an in place zero mean filtering operation to each row in an image.
	 * First two mean values are computed, one for even and one for odd elements,
	 * for each row in the image. Then, the corresponding mean value is subtracted
	 * from each pixel value in the image.
	 * 
	 * @param h - the image height in pixels
	 * @param w - the image width in pixels
	 * @param input - the image stored as a 1D array of float values
	 */
	static void computeMeanHorizontally(int h, int w, float[] input) {
		for (int i = 0; i < h; i++) {
			float sumEven = 0.0f;
			float sumOdd = 0.0f;

			for (int j = 0; j < w - 1; j += 2) {
				sumEven += input[i*w+j];
				sumOdd += input[i*w+j + 1];
			}
			if (!isDivisibleByTwo(w)) {
				sumEven += input[i*w+(w-1)];
			}
	        
			float meanEven = sumEven / ((w + 1) / 2);
			float meanOdd = sumOdd / (w / 2);

			for (int j = 0; j < w - 1; j += 2) {
				input[i*w+j] -= meanEven;
				input[i*w+j + 1] -= meanOdd;
			}
		}
	}

	/**
	 * Applies an in place zero mean filtering operation to each column in an image.
	 * First two mean values are computed, one for even and one for odd elements,
	 * for each column in the image. Then, the corresponding mean value is subtracted
	 * from each pixel value in the image.
	 * 
	 * @param h - the image height in pixels
	 * @param w - the image width in pixels
	 * @param input - the image stored as a 1D array of float values
	 */
	static void computeMeanVertically(int h, int w, float[] input) {
		for (int j = 0; j < w; j++) {
			float sumEven = 0.0f;
			float sumOdd = 0.0f;

			for (int i = 0; i < h-1; i+=2) {
				sumEven += input[i*w+j];
				sumOdd += input[(i+1)*w+j];
			}
			if (!isDivisibleByTwo(h)) {
				sumEven += input[(h-1)*w+j];
			}

			float meanEven = sumEven / (float)((h + 1) / 2);
			float meanOdd = sumOdd / (float)(h / 2);

			for (int i = 0; i < h-1; i+=2) {
				input[i*w+j] -= meanEven;
				input[(i+1)*w+j] -= meanOdd;
			}
	        if (!isDivisibleByTwo(h)) {
	        	input[(h-1)*w+j] -= meanEven;
	        }
		}
	}

	/**
	 * Applies the Zero Mean Total filter on the CPU.
	 * This routine is mainly here for comparing the result
	 * and performance with the GPU version.
	 * 
	 * @param input - the image stored as a 1D array of float values
	 * @return - the filtered image as a 1D float array 
	 */
	public float[] apply1D(float[] input) {
		computeMeanVertically(h, w, input);
		float[] inputTransposed = Util.transpose(h, w, input);
		computeMeanVertically(w, h, inputTransposed);
		float[] transposedBack = Util.transpose(w, h, inputTransposed);
		return Util.assign(input, transposedBack);
	}

	/**
	 * Applies the Zero Mean Total filter on the GPU.
	 * The input image is already in GPU memory and the output is also left
	 * on the GPU.
	 * 
	 * To ensure memory coalescing this routine only applies the zero mean filter
	 * vertically and transposes in between.
	 */
	public void applyGPU() {
		
		//apply zero mean filter vertically
		_computeMeanVertically.launch(_stream, computeMeanVerticallyCol);

		//transpose the result
		_transpose.launch(_stream, transposeForward);

		//apply the vertical filter again to the transposed values
		_computeMeanVertically.launch(_stream, computeMeanVerticallyRow);

		//perform another transpose to get back to the original image layout
		//this kernel is called differently because the width and height
		//of the image have become swapped at the previous transpose
		_transpose.launchKernel((int)Math.ceil((double)h/(double)16), (int)Math.ceil((double)w/(double)16),
				1, 16, 16, 1, 0, _stream, transposeBackward);
		
		//for measuring time
		JCudaDriver.cuCtxSynchronize();
	}
	
	/**
	 * Helper function to see if the width or height of an image is divisible by two.
	 * 
	 * @param value - value of which we want to know whether it is divisible
	 * @return - true if divisible by two, false if not
	 */
    private static boolean isDivisibleByTwo(final int value) {
        return (value & 1) == 0;
    }

    /**
     * cleans up GPU memory
     */
	public void cleanup() {
		_d_output.free();
	}


}