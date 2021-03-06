/*
 * JBoss, Home of Professional Open Source
 * Copyright 2006, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. 
 * See the copyright.txt in the distribution for a full listing 
 * of individual contributors.
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 * 
 * (C) 2005-2006,
 * @author JBoss Inc.
 */
/*
 * Copyright (C) 2005,
 *
 * Arjuna Technologies Ltd,
 * Newcastle upon Tyne,
 * Tyne and Wear,
 * UK.
 *
 * $Id: TransactionImporterImple.java 2342 2006-03-30 13:06:17Z  $
 */

package com.arjuna.ats.internal.jta.transaction.jts.jca;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import com.arjuna.ats.arjuna.common.Uid;
import com.arjuna.ats.internal.jta.transaction.arjunacore.jca.SubordinateTransaction;
import com.arjuna.ats.internal.jta.transaction.arjunacore.jca.TransactionImporter;
import com.arjuna.ats.internal.jta.transaction.jts.subordinate.jca.TransactionImple;
import com.arjuna.ats.jta.xa.XidImple;

public class TransactionImporterImple implements TransactionImporter
{
	
	/**
	 * Create a subordinate transaction associated with the
	 * global transaction inflow. No timeout is associated with the
	 * transaction.
	 * 
	 * @param xid the global transaction.
	 * 
	 * @return the subordinate transaction.
	 * 
	 * @throws XAException thrown if there are any errors.
	 */
	
	public SubordinateTransaction importTransaction (Xid xid) throws XAException
	{
		return importTransaction(xid, 0);
	}

	/**
	 * Create a subordinate transaction associated with the
	 * global transaction inflow and having a specified timeout.
	 * 
	 * @param xid the global transaction.
	 * @param timeout the timeout associated with the global transaction.
	 * 
	 * @return the subordinate transaction.
	 * 
	 * @throws XAException thrown if there are any errors.
	 */
	
	public SubordinateTransaction importTransaction (Xid xid, int timeout) throws XAException
	{
		if (xid == null)
			throw new IllegalArgumentException();

		return addImportedTransaction(null, new XidImple(xid), xid, timeout);
	}

	public SubordinateTransaction recoverTransaction (Uid actId) throws XAException
	{
		if (actId == null)
			throw new IllegalArgumentException();
		
		TransactionImple recovered = new TransactionImple(actId);
		
		if (recovered.baseXid() == null)
		    throw new IllegalArgumentException();

		return addImportedTransaction(recovered, recovered.baseXid(), null, 0);
	}
    
	/**
	 * Get the subordinate (imported) transaction associated with the
	 * global transaction.
	 * 
	 * @param xid the global transaction.
	 * 
	 * @return the subordinate transaction or <code>null</code> if there
	 * is none.
	 * 
	 * @throws XAException thrown if there are any errors.
	 */
	
	public SubordinateTransaction getImportedTransaction (Xid xid) throws XAException
	{
		if (xid == null)
			throw new IllegalArgumentException();

		AtomicReference<SubordinateTransaction> holder = _transactions.get(new XidImple(xid));
		SubordinateTransaction tx = holder == null ? null : holder.get();

		if (tx == null) {
			/*
			 * Remark: if holder != null and holder.get() == null then the setter is about to
			 * import the transaction but has not yet updated the holder. We implement the getter
			 * (the thing that is trying to terminate the imported transaction) as though the imported
			 * transaction only becomes observable when it has been fully imported.
			 */
			return null;
		}

		if (tx.baseXid() == null)
		{
			/*
			 * Try recovery again. If it fails we'll throw a RETRY to the caller who
			 * should try again later.
			 */
            tx.recover();

			return tx;
		}
		else
			return tx;
	}

	/**
	 * Remove the subordinate (imported) transaction.
	 * 
	 * @param xid the global transaction.
	 * 
	 * @throws XAException thrown if there are any errors.
	 */
	
	public void removeImportedTransaction (Xid xid) throws XAException
	{
		if (xid == null)
			throw new IllegalArgumentException();

		_transactions.remove(new XidImple(xid));
	}

	private SubordinateTransaction addImportedTransaction(
			TransactionImple importedTransaction, Xid importedXid, Xid xid, int timeout)
	{
		// We need to store the imported transaction in a volatile field holder so that it can be shared between threads
		AtomicReference<SubordinateTransaction> holder = new AtomicReference<>();
		AtomicReference<SubordinateTransaction> existing;

		if ((existing = _transactions.putIfAbsent(importedXid, holder)) != null) {
			holder = existing;
		}

		SubordinateTransaction txn = holder.get();

		if (txn == null) {
			// retry the get under a lock - this double check idiom is safe because AtomicReference is effectively
			// a volatile so can be concurrently accessed by multiple threads
			synchronized (holder) {
				txn = holder.get();
				if (txn == null) {
					// now it's safe to add the imported transaction to the holder
					if (importedTransaction != null) {
						importedTransaction.recordTransaction();
						txn = importedTransaction;
					} else {
						txn = new TransactionImple(timeout, xid);
					}

					holder.set(txn);
				}
			}
		}

		return txn;
	}

	private static ConcurrentHashMap<Xid, AtomicReference<SubordinateTransaction>> _transactions = new ConcurrentHashMap<>();
}
