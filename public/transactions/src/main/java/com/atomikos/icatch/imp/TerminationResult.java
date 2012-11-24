/**
 * Copyright (C) 2000-2010 Atomikos <info@atomikos.com>
 *
 * This code ("Atomikos TransactionsEssentials"), by itself,
 * is being distributed under the
 * Apache License, Version 2.0 ("License"), a copy of which may be found at
 * http://www.atomikos.com/licenses/apache-license-2.0.txt .
 * You may not use this file except in compliance with the License.
 *
 * While the License grants certain patent license rights,
 * those patent license rights only extend to the use of
 * Atomikos TransactionsEssentials by itself.
 *
 * This code (Atomikos TransactionsEssentials) contains certain interfaces
 * in package (namespace) com.atomikos.icatch
 * (including com.atomikos.icatch.Participant) which, if implemented, may
 * infringe one or more patents held by Atomikos.
 * It should be appreciated that you may NOT implement such interfaces;
 * licensing to implement these interfaces must be obtained separately from Atomikos.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.atomikos.icatch.imp;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Stack;

import com.atomikos.icatch.HeurCommitException;
import com.atomikos.icatch.HeurMixedException;
import com.atomikos.icatch.HeurRollbackException;
import com.atomikos.icatch.HeuristicMessage;
import com.atomikos.icatch.Participant;
import com.atomikos.icatch.RollbackException;
import com.atomikos.icatch.StringHeuristicMessage;
import com.atomikos.icatch.TxState;

/**
 *
 *
 * A result object for termination messages.
 */

class TerminationResult extends Result
{

    protected boolean analyzed_;
    // true if all answers processed

    protected Hashtable<Participant,TxState> heuristicparticipants_;
    // where to put heuristic problems

    protected Hashtable<Participant,TxState> possiblyIndoubts_;
    // for hazard exceptions

    /**
     * Constructor.
     *
     * @param count
     *            The number of messages to process.
     */

    public TerminationResult ( int count )
    {
        super ( count );
        analyzed_ = false;
        heuristicparticipants_ = new Hashtable<Participant,TxState>();
        possiblyIndoubts_ = new Hashtable<Participant,TxState>();
    }

    /**
     * Get the heuristic participants for this termination round.
     *
     * @return Hashtable The heuristic participants, each mapped to its
     *         heuristic state object representation.
     * @exception IllegalStateException
     *                If not done yet.
     */

    public Hashtable<Participant,TxState> getHeuristicParticipants () throws IllegalStateException,
            InterruptedException
    {
        analyze ();

        return heuristicparticipants_;
    }

    /**
     * To get a set of possibly indoubt participants: those with a hazard case.
     *
     * @return Hashtable The list of possibly indoubts.
     * @exception IllegalStateException
     *                If comm. not done yet.
     */

    public Hashtable<Participant,TxState> getPossiblyIndoubts () throws IllegalStateException,
            InterruptedException
    {
        analyze ();
        return possiblyIndoubts_;
    }

    protected synchronized void analyze () throws IllegalStateException,
            InterruptedException

    {
        if ( analyzed_ )
            return;

        boolean heurmixed = false;
        // true asap heurmixed exceptions
        boolean heuraborts = false;
        // true asap heuraborts
        boolean heurcommits = false;
        // true asap heurcommits
        boolean heurhazards = false;
        // true asap heur hazards
        boolean allOK = true;
        // if still true at end -> no problems
        boolean rolledback = false;
        // if true at end -> 1PC and rolledback already

        Stack<Reply> replies = getReplies();
        Enumeration<Reply> enumm = replies.elements ();

        while ( enumm.hasMoreElements () ) {

            Reply reply = (Reply) enumm.nextElement ();

            if ( reply.hasFailed () ) {

                allOK = false;
                Exception err = reply.getException ();
                if ( err instanceof RollbackException ) {
                    // happens during 1pc if tx was rolled back already
                    rolledback = true;
                } else if ( err instanceof HeurMixedException ) {
                    heurmixed = true;
                    HeurMixedException hm = (HeurMixedException) err;
                    addErrorMessages ( hm.getHeuristicMessages () );
                    heuristicparticipants_.put ( reply.getParticipant (),
                            TxState.HEUR_MIXED );
                } else if ( err instanceof HeurCommitException ) {
                    heurcommits = true;
                    HeurCommitException hc = (HeurCommitException) err;
                    addErrorMessages ( hc.getHeuristicMessages () );
                    heurmixed = (heurmixed || heuraborts || heurhazards);
                    heuristicparticipants_.put ( reply.getParticipant (),
                            TxState.HEUR_COMMITTED );

                } else if ( err instanceof HeurRollbackException ) {
                    heuraborts = true;
                    heurmixed = (heurmixed || heurcommits || heurhazards);
                    HeurRollbackException hr = (HeurRollbackException) err;
                    addErrorMessages ( hr.getHeuristicMessages () );
                    heuristicparticipants_.put ( reply.getParticipant (),
                            TxState.HEUR_ABORTED );

                } else {

                    heurhazards = true;
                    heurmixed = (heurmixed || heuraborts || heurcommits);
                    HeuristicMessage heurmsg = new StringHeuristicMessage (
                            "No commit ACK from " + "participant "
                                    + reply.getParticipant () );
                    errmsgvector_.addElement ( heurmsg );
                    heuristicparticipants_.put ( reply.getParticipant (),
                            TxState.HEUR_HAZARD );
                    possiblyIndoubts_.put ( reply.getParticipant (),
                            TxState.HEUR_HAZARD );

                }
            } // if failed reply
            else {

                // if reply OK -> add messages anyway, for complete overview
                // this is in case coordinator makes heur commit
                // So that it can present an overview of what has been
                // heuristically committed.

                HeuristicMessage[] msgs = (HeuristicMessage[]) reply
                        .getResponse ();
                if ( msgs != null )
                    addMessages ( msgs );
            }

        } // while

        if ( rolledback )
            result_ = ROLLBACK;
        else if ( heurmixed || heuraborts
                && heuristicparticipants_.size () != replies.size ()
                || heurcommits
                && heuristicparticipants_.size () != replies.size () )
            result_ = HEUR_MIXED;
        else if ( heurhazards ) {
            // heur hazard BEFORE heur abort or commit!
            // see OTS definitions: hazard ASA some unknown, 
        	// but ALL KNOWN ARE COMMIT OR ALL ARE ABORT
            result_ = HEUR_HAZARD;
        } else if ( heuraborts ) {
            result_ = HEUR_ROLLBACK;
            // here, there can be no heur commits as well, since otherwise mixed
            // would have fitted. Same for hazards.

        } else if ( heurcommits ) {
            // here, there can be no heur aborts as well, since mixed would have
            // fitted. no hazards either, since hazards would have fitted.
            result_ = HEUR_COMMIT;

        } else if ( allOK )
            result_ = ALL_OK;

        analyzed_ = true;
    }

}
