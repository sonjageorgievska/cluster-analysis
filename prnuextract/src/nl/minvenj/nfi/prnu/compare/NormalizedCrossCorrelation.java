/*
 * Copyright (c) 2012-2013, Netherlands Forensic Institute
 * All rights reserved.
 */
package nl.minvenj.nfi.prnu;


import nl.minvenj.nfi.prnu.Util;
import nl.minvenj.nfi.cuba.cudaapi.*;
import jcuda.*;
import jcuda.runtime.JCuda;
import jcuda.runtime.cudaStream_t;
import jcuda.runtime.cudaDeviceProp;
import jcuda.runtime.cudaError;
import jcuda.driver.*;



/**
 * This class is performs a Normalized Cross Correlation on the GPU
 */
public class NormalizedCrossCorrelation {

    //cuda handles
    protected CudaContext _context;
    protected CudaStream _stream;

    //references to GPU memory
    protected CudaMemFloat _d_input1;
    protected CudaMemFloat _d_input2;
    protected CudaMemDouble _d_output;

    protected CudaMemFloat _d_x_patterns[];
    protected CudaMemFloat _d_y_patterns[];

    //handles to CUDA kernels
    protected CudaFunction _sumSquared;
    protected CudaFunction _computeNCC;
    protected CudaFunction _sumDoubles;

    //parameterlists for kernel invocations
    protected Pointer sumSquared;
    protected Pointer sumDoubles;
    protected Pointer computeNCC;

    //PRNU pattern dimensions
    int h;
    int w;
    int n;

    int num_patterns;

    public NormalizedCrossCorrelation(int h, int w, CudaContext context, CudaModule module, CudaModule lib) {
        _context = context;
        _stream = new CudaStream();
        this.h = h;
        this.w = w;
        this.n = h*w;

        //setup CUDA functions
        JCudaDriver.setExceptionsEnabled(true);
        int threads = 1024;
        int reducing_thread_blocks = 1; //optimally this equals the number of SMs in the GPU

        //get number of SMs
        int num_sm =_context.getDevice().getComputeModules();
        System.out.println("detected " + num_sm + " SMs on GPU");
        reducing_thread_blocks = num_sm;

        _sumSquared = module.getFunction("sumSquared");
        _sumSquared.setDim(   reducing_thread_blocks, 1, 1,
                threads, 1, 1);

        _computeNCC = module.getFunction("computeNCC");
        _computeNCC.setDim(   reducing_thread_blocks, 1, 1,
                threads, 1, 1);

        _sumDoubles = lib.getFunction("sumDoubles");
        _sumDoubles.setDim(   1, 1, 1,
                threads, 1, 1);

        //allocate local variables in GPU memory
        _d_input1 = _context.allocFloats(h*w);
        _d_input2 = _context.allocFloats(h*w);
        _d_output = _context.allocDoubles(num_sm);

        long free[] = new long[1];
        long total[] = new long[1];
        JCuda.cudaMemGetInfo(free, total);
        long patternSize = h*w*4; //size of pattern on the GPU
        int fit_patterns = (int)(free[0] / patternSize); //debugging
        num_patterns = fit_patterns/2 + 1;
        System.out.println("Allocating space for in total " + 2*num_patterns + " patterns on the GPU");

        _d_x_patterns = new CudaMemFloat[num_patterns];
        _d_y_patterns = new CudaMemFloat[num_patterns];

        _d_x_patterns[0] = _d_input1;
        _d_y_patterns[0] = _d_input2;
        for (int i=1; i<num_patterns; i++) {
            _d_x_patterns[i] = _context.allocFloats(h*w);
            _d_y_patterns[i] = _context.allocFloats(h*w);
        }

        //construct parameter lists for the CUDA kernels
        sumSquared = Pointer.to(
                Pointer.to(_d_output.getDevicePointer()),
                Pointer.to(_d_input1.getDevicePointer()),
                Pointer.to(new int[]{n})
                );

        sumDoubles = Pointer.to(
                Pointer.to(_d_output.getDevicePointer()),
                Pointer.to(_d_output.getDevicePointer()),
                Pointer.to(new int[]{num_sm})
                );

        computeNCC = Pointer.to(
                Pointer.to(_d_output.getDevicePointer()),
                Pointer.to(_d_input1.getDevicePointer()),
                Pointer.to(_d_input2.getDevicePointer()),
                Pointer.to(new int[]{n})
        );


    }

    public double[][] compareGPU(float[][] xPatterns, double[] sumsq_x, float[][] yPatterns, double[] sumsq_y, boolean[][] predicate) {

        double result[][] = new double[num_patterns][num_patterns];

        //copy inputs to the GPU (host to device)
        for (int i=0; i < num_patterns; i++) {
            if (xPatterns[i] != null) {
                _d_x_patterns[i].copyHostToDeviceAsync(xPatterns[i], _stream);
            }
            if (yPatterns[i] != null) {
                _d_y_patterns[i].copyHostToDeviceAsync(yPatterns[i], _stream);
            }
        }

        //call the kernel
        _stream.synchronize();
        long start = System.nanoTime();

        for (int i=0; i < num_patterns; i++) {
            for (int j=0; j < num_patterns; j++) {
                if (predicate[i][j]) {

                    //call the kernel
                    computeNCC = Pointer.to(
                            Pointer.to(_d_output.getDevicePointer()),
                            Pointer.to(_d_x_patterns[i].getDevicePointer()),
                            Pointer.to(_d_y_patterns[j].getDevicePointer()),
                            Pointer.to(new int[]{n})
                    );
                    _computeNCC.launch(_stream, computeNCC);
                    _sumDoubles.launch(_stream, sumDoubles);

                    //copy output (device to host)
                    double out[] = new double[1];
                    _d_output.copyDeviceToHostAsync(out, 1, _stream);
                    _stream.synchronize();
                    result[i][j] = out[0] / Math.sqrt(sumsq_x[i] * sumsq_y[j]);

                } else {
                    result[i][j] = 0.0;
                }
            }
        }

        _stream.synchronize();

        double gpu_time = (System.nanoTime() - start)/1e6;
        System.out.println("computeNCC GPU took: " + gpu_time + " ms.");

        return result;
    }


    public double compareGPU(float[] x, double sumsq_x, float[] y, double sumsq_y) {
        //copy inputs to the GPU (host to device)
        _d_input1.copyHostToDeviceAsync(x, _stream);
        _d_input2.copyHostToDeviceAsync(y, _stream);

        //call the kernel
        _stream.synchronize();
        long start = System.nanoTime();
        _computeNCC.launch(_stream, computeNCC);
        _sumDoubles.launch(_stream, sumDoubles);
        _stream.synchronize();

        //copy output (device to host)
        double result[] = new double[1];
        _d_output.copyDeviceToHostAsync(result, 1, _stream);
        _stream.synchronize();
        double res = result[0] / Math.sqrt(sumsq_x * sumsq_y);

        double gpu_time = (System.nanoTime() - start)/1e6;
        System.out.println("computeNCC GPU: " + res + " took: " + gpu_time + " ms.");

        return res;
    }

    public double sumSquaredGPU(float[] pattern) {
        //copy input to GPU
        _d_input1.copyHostToDeviceAsync(pattern, _stream);

        _stream.synchronize();
        long start = System.nanoTime();

        //call the kernel
        _sumSquared.launch(_stream, sumSquared);
        _sumDoubles.launch(_stream, sumDoubles);

        //copy output (device to host)
        double result[] = new double[1];
        _d_output.copyDeviceToHostAsync(result, 1, _stream);
        _stream.synchronize();

        double gpu_time = (System.nanoTime() - start)/1e6;
        System.out.println("sumSquared GPU: " + result[0] + " took: " + gpu_time + " ms.");

        return result[0];
    }

    public static double sumSquared(final float[] pattern) {
	    double sumsq = 0.0;
	    for (int i=0; i<pattern.length; i++) {
	        sumsq += pattern[i] * pattern[i];
	    }
	    return sumsq;
    }

    public static double compare(final float[] x, double sumsq_x, final float[] y, double sumsq_y) {
	    double sum_xy = 0.0;
            for (int i=0; i<x.length; i++) {
                sum_xy += x[i] * y[i];
       	    }
    	return (sum_xy / Math.sqrt(sumsq_x * sumsq_y));
    }

}