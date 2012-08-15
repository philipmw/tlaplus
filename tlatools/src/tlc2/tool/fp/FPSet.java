// Copyright (c) 2003 Compaq Corporation.  All rights reserved.
// Portions Copyright (c) 2003 Microsoft Corporation.  All rights reserved.
// Last modified on Mon 30 Apr 2007 at 13:13:19 PST by lamport
//      modified on Tue May 15 11:44:57 PDT 2001 by yuanyu

package tlc2.tool.fp;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;

import tlc2.TLCGlobals;
import tlc2.output.EC;
import tlc2.output.MP;
import tlc2.tool.distributed.fp.FPSetManager;
import tlc2.tool.distributed.fp.FPSetRMI;
import tlc2.util.BitVector;
import tlc2.util.LongVec;
import util.FileUtil;
import util.TLCRuntime;

/**
 * An <code>FPSet</code> is a set of 64-bit fingerprints.
 *
 * Note: All concrete subclasses of this class are required to
 * guarantee that their methods are thread-safe.
 */
//TODO refactor to separate abstract FPSet and factory 
@SuppressWarnings("serial")
public abstract class FPSet extends UnicastRemoteObject implements FPSetRMI
{
	/**
	 * Allows users to overwrite the internally used {@link FPSet} impl by their
	 * own. In order to load the class, it:<ul><li> 
	 * has to appear on the class path</li>
	 * <li>has to support a two-args (int, long) constructor </li>
	 * <li>extend {@link FPSet}</li></ul>
	 * Both is the user's responsibility.
	 */
	private static final String USER_FPSET_IMPL_CLASSNAME = System.getProperty(
			FPSet.class.getName() + ".impl", null);
	
	/**
	 * Size of a Java long in bytes
	 */
	protected static final int LongSize = 8;

	/**
	 * @see FPSet#getFPSet(int, long)
	 * @return
	 * @throws RemoteException 
	 */
	public static FPSet getFPSet() throws RemoteException {
		return getFPSet(0, -1);
	}
	
	/**
	 * Creates a new {@link FPSet} depending on what the caller wants.
	 * @param fpBits if 0, a {@link DiskFPSet} will be returned, a {@link MultiFPSet} otherwise.
	 * @param fpMemSizeInBytes
	 * @return
	 * @throws RemoteException
	 */
	public static FPSet getFPSet(int fpBits, long fpMemSizeInBytes) throws RemoteException {
		return getFPSet(fpBits, fpMemSizeInBytes, true);
	}
	
	static FPSet getFPSet(int fpBits, long fpMemSizeInBytes, boolean multiFPSet) throws RemoteException {
		// convert from physical mem into logical amount of fingerprints
		long fpMemSizeInFPs = fpMemSizeInBytes / LongSize;
		
		FPSet set = null;
		
		//TODO handle case where fpbits > 0 and custom factory set which does not take fpbits into account
		if (loadCustomFactory() && multiFPSet == false) {
			set = loadCustomFactory(USER_FPSET_IMPL_CLASSNAME, fpBits, fpMemSizeInFPs);
		}
		
		if (set == null && fpBits == 0) {
			set = new LSBDiskFPSet(fpMemSizeInFPs);
		} else if (set == null) {
			// Pass physical memory instead of logical FP count to adhere to the
			// general FPSet ctor contract.
			// @see http://bugzilla.tlaplus.net/show_bug.cgi?id=290
			if (loadCustomFactory() && msbBasedFPSet(USER_FPSET_IMPL_CLASSNAME)) {
				set = new MSBMultiFPSet(fpBits, fpMemSizeInBytes);
			} else {
				set = new MultiFPSet(fpBits, fpMemSizeInBytes);
			}
		}
		return set;
	}

	/**
	 * @param userFpsetImplClassname
	 * @return true iff the given class uses the MSB to pre-sort its fingerprints
	 */
	private static boolean msbBasedFPSet(String userFpsetImplClassname) {
		if (!allocatesOnHeap(userFpsetImplClassname)) {
			return true;
		}
		if (userFpsetImplClassname.equals(MSBDiskFPSet.class.getName())) {
			return true;
		}
		return false;
	}

	/**
	 * @return true iff a custom factory should be loaded
	 */
	private static boolean loadCustomFactory() {
		return USER_FPSET_IMPL_CLASSNAME != null;
	}
	
	/**
	 * This block of code tries to load the given class with the FPSet class
	 * loader. Thus, the class has to be available to it.
	 * 
	 * @param clazz
	 * @param fpMemSizeInBytes 
	 * @param fpBits 
	 * @return
	 */
	private static FPSet loadCustomFactory(final String clazz, int fpBits, long fpMemSizeInFPs) {
		Exception exp = null;
		try {
			// poor mans version of modularity, booh!
			final ClassLoader classLoader = FPSet.class.getClassLoader();
			final Class<?> factoryClass = classLoader.loadClass(clazz);
			
			// HACK class loading to pass _non heap_ memory into subclasses of
			// OffHeapFPSet.
			if (!allocatesOnHeap(clazz)) {
				long l = TLCRuntime.getInstance().getNonHeapPhysicalMemory() / (long) LongSize;
				// divide l among all FPSet instances
				fpMemSizeInFPs = l >> fpBits; 
			}

			final Constructor<?> constructor = factoryClass
					.getDeclaredConstructor(new Class[] { long.class, int.class });
			final Object instance = constructor.newInstance(
					fpMemSizeInFPs, fpBits);
			// sanity check if given class from string implements FPSet
			if (instance instanceof FPSet) {
				return (FPSet) instance;
			}
		} catch (ClassNotFoundException e) {
			exp = e;
		} catch (InstantiationException e) {
			exp = e;
		} catch (IllegalAccessException e) {
			exp = e;
		} catch (SecurityException e) {
			exp = e;
		} catch (NoSuchMethodException e) {
			exp = e;
		} catch (IllegalArgumentException e) {
			exp = e;
		} catch (InvocationTargetException e) {
			exp = e;
		}
		// LL modified error message on 7 April 2012
		MP.printWarning(EC.GENERAL, "unsuccessfully trying to load custom FPSet class: " + clazz, exp);
		return null;
	}
	
	/**
	 * Counts the amount of states passed to the containsBlock method
	 */
	//TODO need AtomicLong here to prevent dirty writes to statesSeen?
	protected long statesSeen = 0L;
	
    protected FPSet() throws RemoteException
    { /*SKIP*/
    }

    /**
     * Performs any initialization necessary to handle "numThreads"
     * worker threads and one main thread. Subclasses will need to
     * override this method as necessary. This method must be called
     * after the constructor but before any of the other methods below.
     */
    public abstract void init(int numThreads, String metadir, String filename) throws IOException;

    /* Returns the number of fingerprints in this set. */
    public abstract long size();

    /**
     * Returns <code>true</code> iff the fingerprint <code>fp</code> is
     * in this set. If the fingerprint is not in the set, it is added to
     * the set as a side-effect.
     */
    public abstract boolean put(long fp) throws IOException;

    /**
     * Returns <code>true</code> iff the fingerprint <code>fp</code> is
     * in this set.
     */
    public abstract boolean contains(long fp) throws IOException;

    public void close()
    { /*SKIP*/
    }

    public void addThread() throws IOException
    { /*SKIP*/
    }

    public abstract void exit(boolean cleanup) throws IOException;

    public abstract double checkFPs() throws IOException;

    public abstract void beginChkpt() throws IOException;

    public abstract void commitChkpt() throws IOException;

    public abstract void recover() throws IOException;

    public abstract void recoverFP(long fp) throws IOException;

    public abstract void prepareRecovery() throws IOException;

    public abstract void completeRecovery() throws IOException;

    /* The set of checkpoint methods for remote checkpointing. */
    public abstract void beginChkpt(String filename) throws IOException;

    public abstract void commitChkpt(String filename) throws IOException;

    public abstract void recover(String filename) throws IOException;
    
	public boolean checkInvariant() throws IOException {
		return true;
	}

    public BitVector putBlock(LongVec fpv) throws IOException
    {
        int size = fpv.size();
		BitVector bv = new BitVector(size);
        for (int i = 0; i < fpv.size(); i++)
        {
            if (!this.put(fpv.elementAt(i)))
            {
                bv.set(i);
            }
        }
        return bv;
    }

    public BitVector containsBlock(LongVec fpv) throws IOException
    {
    	statesSeen += fpv.size();
        BitVector bv = new BitVector(fpv.size());
        for (int i = 0; i < fpv.size(); i++)
        {
            if (!this.contains(fpv.elementAt(i)))
            {
                bv.set(i);
            }
        }
        return bv;
    }

    /* (non-Javadoc)
     * @see tlc2.tool.distributed.FPSetRMI#getStatesSeen()
     */
    public long getStatesSeen() throws RemoteException {
    	return statesSeen;
    }

	/**
	 * @param fpBits
	 * @return
	 */
	public static boolean isValid(int fpBits) {
		return fpBits >= 0 && fpBits <= MultiFPSet.MAX_FPBITS;
	}

	/**
	 * @see UnicastRemoteObject#unexportObject(java.rmi.Remote, boolean)
	 * @param force
	 * @throws NoSuchObjectException
	 */
	public void unexportObject(boolean force) throws NoSuchObjectException {
		UnicastRemoteObject.unexportObject(this, force);
	}

    // SZ Jul 10, 2009: this method is not used
    public static void main(String args[])
    {
        System.out.println("TLC FP Server " + TLCGlobals.versionOfTLC);

        String metadir = null;
        String fromChkpt = null;
		int fpBits = 0;
		
		int index = 0;

		while (index < args.length) {
			if (args[index].equals("-fpbits")) {
				index++;
				if (index < args.length) {
					try {
						fpBits = Integer.parseInt(args[index]);

						// make sure it's in valid range
						if (!FPSet.isValid(fpBits)) {
							printErrorMsg("Error: Value in interval [0, 30] for fpbits required. But encountered "
									+ args[index]);
							System.exit(0);
						}

						index++;
					} catch (Exception e) {
						printErrorMsg("Error: A number for -fpbits is required. But encountered "
								+ args[index]);
						System.exit(0);
					}
				} else {
					printErrorMsg("Error: expect an integer for -workers option.");
					System.exit(0);
				}
			} else if (args[index].charAt(0) == '-') {
				printErrorMsg("Error: unrecognized option: " + args[index]);
				System.exit(0);
			} 
			if (metadir != null) {
				printErrorMsg("Error: more than one directory for metadata: "
						+ metadir + " and " + args[index]);
				System.exit(0);
			}
			metadir = args[index++] + FileUtil.separator;
		}

        String hostname = "Unknown";
        try
        {
            hostname = InetAddress.getLocalHost().getHostName();
            metadir = (metadir == null) ? hostname : (metadir + hostname);
            File filedir = new File(metadir);
            if (!filedir.exists())
            {
                boolean created = filedir.mkdirs();
                if (!created)
                {
                    System.err
                            .println("Error: fingerprint server could not make a directory for the disk files it needs to write.\n");
                    System.exit(0);
                }
            }
			// Start memory-based fingerprint set server.
            // Note: It would be wrong to use the disk-based implementation.
			FPSet fpSet = FPSet.getFPSet(fpBits, -1);
            fpSet.init(1, metadir, "fpset");
            if (fromChkpt != null)
            {
                fpSet.recover(); // recover when instructed
            }
            Registry rg = LocateRegistry.createRegistry(FPSetManager.Port);
            rg.rebind("FPSetServer", fpSet);
            System.out.println("Fingerprint set server at " + hostname + " is ready.");

            synchronized (fpSet)
            {
                while (true)
                {
                    System.out.println("Progress: The number of fingerprints stored at " + hostname + " is "
                            + fpSet.size() + ".");
                    fpSet.wait(300000);
                }
            }
        } catch (Exception e)
        {
            System.out.println(hostname + ": Error: " + e.getMessage());
        }
    }
    private static void printErrorMsg(String msg)
    {
        System.out.println(msg);
        System.out.println("Usage: java tlc2.tool.FPSet [-option] metadir");
    }

	/**
	 * @return A list of classes implementing {@link FPSet}. Eventually this
	 *         list should be constructed dynamically during runtime.
	 */
	public static String[] getImplementations() {
		final List<String> l = new ArrayList<String>();
		
		l.add(LSBDiskFPSet.class.getName());
		l.add(MSBDiskFPSet.class.getName());

		l.add(MemFPSet.class.getName());
		l.add(MemFPSet1.class.getName());
		l.add(MemFPSet2.class.getName());
		
		l.add(OffHeapDiskFPSet.class.getName());
		l.add(TroveOffHeapDiskFPSet.class.getName());
		l.add(WaitFreeOffHeapDiskFPSet.class.getName());

		return l.toArray(new String[l.size()]);
	}
	
	/**
	 * @return The default for {@link FPSet#getImplementations()}
	 */
	public static String getImplementationDefault() {
		return LSBDiskFPSet.class.getName();
	}

	public static boolean allocatesOnHeap(final Class clazz) {
		return !OffHeapDiskFPSet.class.isAssignableFrom(clazz);
	}
	
	public static boolean allocatesOnHeap(final String clazz) {
		try {
			final ClassLoader classLoader = FPSet.class.getClassLoader();
			Class<?> cls = classLoader.loadClass(clazz);
			return allocatesOnHeap(cls);
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
}
