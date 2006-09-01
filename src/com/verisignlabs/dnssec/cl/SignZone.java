// $Id$
//
// Copyright (C) 2001-2003 VeriSign, Inc.
//
// This library is free software; you can redistribute it and/or
// modify it under the terms of the GNU Lesser General Public
// License as published by the Free Software Foundation; either
// version 2.1 of the License, or (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
// USA

package com.verisignlabs.dnssec.cl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.apache.commons.cli.*;
import org.apache.commons.cli.Options;

import org.xbill.DNS.*;
import org.xbill.DNS.utils.base16;

import com.verisignlabs.dnssec.security.*;

/**
 * This class forms the command line implementation of a DNSSEC zone signer.
 * 
 * @author David Blacka (original)
 * @author $Author$
 * @version $Revision$
 */
public class SignZone
{
  private static Logger log;

  /**
   * This is an inner class used to hold all of the command line option state.
   */
  private static class CLIState
  {
    private Options opts;
    private File    keyDirectory    = null;
    public File     keysetDirectory = null;
    public String[] kskFiles        = null;
    public String[] keyFiles        = null;
    public String   zonefile        = null;
    public Date     start           = null;
    public Date     expire          = null;
    public String   outputfile      = null;
    public boolean  verifySigs      = false;
    public boolean  selfSignKeys    = true;
    public boolean  useOptOut       = false;
    public boolean  fullySignKeyset = false;
    public List     includeNames    = null;
    public boolean  useNsec3        = false;
    public byte[]   salt            = null;
    public int      iterations      = 0;
    public int      digest_id       = DSRecord.SHA1_DIGEST_ID;

    public CLIState()
    {
      setupCLI();
    }

    /**
     * Set up the command line options.
     * 
     * @return a set of command line options.
     */
    private void setupCLI()
    {
      opts = new Options();

      // boolean options
      opts.addOption("h", "help", false, "Print this message.");
      opts.addOption("a", "verify", false, "verify generated signatures>");
      opts.addOption("F",
          "fully-sign-keyset",
          false,
          "sign the zone apex keyset with all available keys.");

      // Argument options
      opts.addOption(OptionBuilder.hasOptionalArg().withLongOpt("verbose")
          .withArgName("level").withDescription("verbosity level.")
          .create('v'));
      opts.addOption(OptionBuilder.hasArg().withArgName("dir")
          .withLongOpt("keyset-directory")
          .withDescription("directory to find keyset files (default '.').")
          .create('d'));
      opts.addOption(OptionBuilder.hasArg().withArgName("dir")
          .withLongOpt("key-directory")
          .withDescription("directory to find key files (default '.').")
          .create('D'));
      opts.addOption(OptionBuilder.hasArg().withArgName("time/offset")
          .withLongOpt("start-time")
          .withDescription("signature starting time "
              + "(default is now - 1 hour)").create('s'));
      opts.addOption(OptionBuilder.hasArg().withArgName("time/offset")
          .withLongOpt("expire-time")
          .withDescription("signature expiration time "
              + "(default is start-time + 30 days).").create('e'));
      opts.addOption(OptionBuilder.hasArg().withArgName("outfile")
          .withDescription("file the signed zone is written to "
              + "(default is <origin>.signed).").create('f'));
      opts.addOption(OptionBuilder.hasArg().withArgName("KSK file")
          .withLongOpt("ksk-file")
          .withDescription("this key is a key signing key (may repeat).")
          .create('k'));
      opts.addOption(OptionBuilder.hasArg().withArgName("file")
          .withLongOpt("include-file")
          .withDescription("include names in this "
              + "file in the NSEC/NSEC3 chain.").create('I'));

      // NSEC3 options
      opts.addOption("3", "use-nsec3", false, "use NSEC3 instead of NSEC");
      opts.addOption("O",
          "use-opt-out",
          false,
          "generate a fully Opt-Out zone (only valid with NSEC3).");

      opts.addOption(OptionBuilder.hasArg().withLongOpt("salt")
          .withArgName("hex value").withDescription("supply a salt value.")
          .create('S'));
      opts.addOption(OptionBuilder.hasArg().withLongOpt("random-salt")
          .withArgName("length").withDescription("generate a random salt.")
          .create('R'));
      opts.addOption(OptionBuilder.hasArg().withLongOpt("iterations")
          .withArgName("value")
          .withDescription("use this value for the iterations in NSEC3.")
          .create());

      opts.addOption(OptionBuilder.hasArg()
          .withArgName("alias:original:mnemonic").withLongOpt("alg-alias")
          .withDescription("Define an alias for an algorithm (may repeat).")
          .create('A'));
      opts.addOption(OptionBuilder.hasArg().withArgName("id")
          .withLongOpt("ds-digest")
          .withDescription("Digest algorithm to use for generated DSs")
          .create());
    }

    public void parseCommandLine(String[] args)
        throws org.apache.commons.cli.ParseException, ParseException,
        IOException
    {
      CommandLineParser cli_parser = new PosixParser();
      CommandLine cli = cli_parser.parse(opts, args);

      String optstr = null;
      String[] optstrs = null;

      if (cli.hasOption('h')) usage();

      if (cli.hasOption('v'))
      {
        int value = parseInt(cli.getOptionValue('v'), 5);
        Logger rootLogger = Logger.getLogger("");

        switch (value)
        {
          case 0 :
            rootLogger.setLevel(Level.OFF);
            break;
          case 4 :
          default :
            rootLogger.setLevel(Level.INFO);
            break;
          case 5 :
            rootLogger.setLevel(Level.FINE);
            break;
          case 6 :
            rootLogger.setLevel(Level.ALL);
            break;
        }
        Handler[] handlers = rootLogger.getHandlers();
        for (int i = 0; i < handlers.length; i++)
          handlers[i].setLevel(rootLogger.getLevel());
      }

      if (cli.hasOption('a')) verifySigs = true;
      if (cli.hasOption('3')) useNsec3 = true;
      if (cli.hasOption('O')) useOptOut = true;

      if (useOptOut && !useNsec3)
      {
        System.err.println("Opt-Out not supported without NSEC3 -- ignored.");
        useOptOut = false;
      }

      if ((optstrs = cli.getOptionValues('A')) != null)
      {
        for (int i = 0; i < optstrs.length; i++)
        {
          addArgAlias(optstrs[i]);
        }
      }

      if (cli.hasOption('F')) fullySignKeyset = true;

      if ((optstr = cli.getOptionValue('d')) != null)
      {
        keysetDirectory = new File(optstr);
        if (!keysetDirectory.isDirectory())
        {
          System.err.println("error: " + optstr + " is not a directory");
          usage();

        }
      }

      if ((optstr = cli.getOptionValue('D')) != null)
      {
        keyDirectory = new File(optstr);
        if (!keyDirectory.isDirectory())
        {
          System.err.println("error: " + optstr + " is not a directory");
          usage();
        }
      }

      if ((optstr = cli.getOptionValue('s')) != null)
      {
        start = convertDuration(null, optstr);
      }
      else
      {
        // default is now - 1 hour.
        start = new Date(System.currentTimeMillis() - (3600 * 1000));
      }

      if ((optstr = cli.getOptionValue('e')) != null)
      {
        expire = convertDuration(start, optstr);
      }
      else
      {
        expire = convertDuration(start, "+2592000"); // 30 days
      }

      outputfile = cli.getOptionValue('f');

      kskFiles = cli.getOptionValues('k');

      if ((optstr = cli.getOptionValue('I')) != null)
      {
        File includeNamesFile = new File(optstr);
        includeNames = getNameList(includeNamesFile);
      }

      if ((optstr = cli.getOptionValue('S')) != null)
      {
        salt = base16.fromString(optstr);
        if (salt == null && !optstr.equals("-"))
        {
          System.err.println("error: salt is not valid hexidecimal.");
          usage();
        }
      }

      if ((optstr = cli.getOptionValue('R')) != null)
      {
        int length = parseInt(optstr, 0);
        if (length > 0 && length <= 255)
        {
          Random random = new Random();
          salt = new byte[length];
          random.nextBytes(salt);
        }
      }

      if ((optstr = cli.getOptionValue("iterations")) != null)
      {
        iterations = parseInt(optstr, iterations);
        if (iterations < 0 || iterations > 8388607)
        {
          System.err.println("error: iterations value is invalid");
          usage();
        }
      }

      if ((optstr = cli.getOptionValue("ds-digest")) != null)
      {
        digest_id = parseInt(optstr, -1);
        if (digest_id < 0)
        {
          System.err.println("error: DS digest ID is not a valid identifier");
          usage();
        }
      }

      String[] files = cli.getArgs();

      if (files.length < 1)
      {
        System.err.println("error: missing zone file and/or key files");
        usage();
      }

      zonefile = files[0];
      if (files.length > 1)
      {
        keyFiles = new String[files.length - 1];
        System.arraycopy(files, 1, keyFiles, 0, files.length - 1);
      }
    }

    private void addArgAlias(String s)
    {
      if (s == null) return;

      DnsKeyAlgorithm algs = DnsKeyAlgorithm.getInstance();

      String[] v = s.split(":");
      if (v.length < 2) return;

      int alias = parseInt(v[0], -1);
      if (alias <= 0) return;
      int orig = parseInt(v[1], -1);
      if (orig <= 0) return;
      String mn = null;
      if (v.length > 2) mn = v[2];

      algs.addAlias(alias, mn, orig);
    }

    /** Print out the usage and help statements, then quit. */
    private void usage()
    {
      HelpFormatter f = new HelpFormatter();

      PrintWriter out = new PrintWriter(System.err);

      // print our own usage statement:
      out.println("usage: signZone.sh [..options..] "
          + "zone_file [key_file ...] ");
      f.printHelp(out,
          75,
          "signZone.sh",
          null,
          opts,
          HelpFormatter.DEFAULT_LEFT_PAD,
          HelpFormatter.DEFAULT_DESC_PAD,
          "\ntime/offset = YYYYMMDDHHmmss|+offset|\"now\"+offset\n");

      out.flush();
      System.exit(64);
    }
  }

  /**
   * This is just a convenience method for parsing integers from strings.
   * 
   * @param s the string to parse.
   * @param def the default value, if the string doesn't parse.
   * @return the parsed integer, or the default.
   */
  private static int parseInt(String s, int def)
  {
    try
    {
      int v = Integer.parseInt(s);
      return v;
    }
    catch (NumberFormatException e)
    {
      return def;
    }
  }

  /**
   * Verify the generated signatures.
   * 
   * @param zonename the origin name of the zone.
   * @param records a list of {@link org.xbill.DNS.Record}s.
   * @param keypairs a list of keypairs used the sign the zone.
   * @return true if all of the signatures validated.
   */
  private static boolean verifyZoneSigs(Name zonename, List records,
      List keypairs)
  {
    boolean secure = true;

    DnsSecVerifier verifier = new DnsSecVerifier();

    for (Iterator i = keypairs.iterator(); i.hasNext();)
    {
      verifier.addTrustedKey((DnsKeyPair) i.next());
    }

    verifier.setVerifyAllSigs(true);

    List rrsets = SignUtils.assembleIntoRRsets(records);

    for (Iterator i = rrsets.iterator(); i.hasNext();)
    {
      RRset rrset = (RRset) i.next();

      // skip unsigned rrsets.
      if (!rrset.sigs().hasNext()) continue;

      int result = verifier.verify(rrset, null);

      if (result != DNSSEC.Secure)
      {
        log.fine("Signatures did not verify for RRset: (" + result + "): "
            + rrset);
        secure = false;
      }
    }

    return secure;
  }

  /**
   * Load the key pairs from the key files.
   * 
   * @param keyfiles a string array containing the base names or paths of the
   *          keys to be loaded.
   * @param start_index the starting index of keyfiles string array to use.
   *          This allows us to use the straight command line argument array.
   * @param inDirectory the directory to look in (may be null).
   * @return a list of keypair objects.
   */
  private static List getKeys(String[] keyfiles, int start_index,
      File inDirectory) throws IOException
  {
    if (keyfiles == null) return null;

    int len = keyfiles.length - start_index;
    if (len <= 0) return null;

    ArrayList keys = new ArrayList(len);

    for (int i = start_index; i < keyfiles.length; i++)
    {
      DnsKeyPair k = BINDKeyUtils.loadKeyPair(keyfiles[i], inDirectory);
      if (k != null) keys.add(k);
    }

    return keys;
  }

  /**
   * This is an implementation of a file filter used for finding BIND 9-style
   * keyset-* files.
   */
  private static class KeysetFileFilter implements FileFilter
  {
    public boolean accept(File pathname)
    {
      if (!pathname.isFile()) return false;
      String name = pathname.getName();
      if (name.startsWith("keyset-")) return true;
      return false;
    }
  }

  /**
   * Load keysets (which contain delegation point security info).
   * 
   * @param inDirectory the directory to look for the keyset files (may be
   *          null, in which case it defaults to looking in the current
   *          working directory).
   * @param zonename the name of the zone we are signing, so we can ignore
   *          keysets that do not belong in the zone.
   * @return a list of {@link org.xbill.DNS.Record}s found in the keyset
   *         files.
   */
  private static List getKeysets(File inDirectory, Name zonename)
      throws IOException
  {
    if (inDirectory == null)
    {
      inDirectory = new File(".");
    }

    // get the list of "keyset-" files.
    FileFilter filter = new KeysetFileFilter();
    File[] files = inDirectory.listFiles(filter);

    // read in all of the records
    ArrayList keysetRecords = new ArrayList();
    for (int i = 0; i < files.length; i++)
    {
      List l = ZoneUtils.readZoneFile(files[i].getAbsolutePath(), zonename);
      keysetRecords.addAll(l);
    }

    // discard records that do not belong to the zone in question.
    for (Iterator i = keysetRecords.iterator(); i.hasNext();)
    {
      Record r = (Record) i.next();
      if (!r.getName().subdomain(zonename))
      {
        i.remove();
      }
    }

    return keysetRecords;
  }

  /**
   * Load a list of DNS names from a file.
   * 
   * @param nameListFile the path of a file containing a bare list of DNS
   *          names.
   * @return a list of {@link org.xbill.DNS.Name} objects.
   */
  private static List getNameList(File nameListFile) throws IOException
  {
    BufferedReader br = new BufferedReader(new FileReader(nameListFile));
    List res = new ArrayList();

    String line = null;
    while ((line = br.readLine()) != null)
    {
      try
      {
        Name n = Name.fromString(line);
        // force the name to be absolute.
        // FIXME: we should probably get some fancy logic here to
        // detect if the name needs the origin appended, or just the
        // root.
        if (!n.isAbsolute()) n = Name.concatenate(n, Name.root);

        res.add(n);
      }
      catch (TextParseException e)
      {
        log.severe("DNS Name parsing error:" + e);
      }
    }

    if (res.size() == 0) return null;
    return res;
  }

  /**
   * Calculate a date/time from a command line time/offset duration string.
   * 
   * @param start the start time to calculate offsets from.
   * @param duration the time/offset string to parse.
   * @return the calculated time.
   */
  private static Date convertDuration(Date start, String duration)
      throws ParseException
  {
    if (start == null) start = new Date();
    if (duration.startsWith("now"))
    {
      start = new Date();
      if (duration.indexOf("+") < 0) return start;

      duration = duration.substring(3);
    }

    if (duration.startsWith("+"))
    {
      long offset = (long) parseInt(duration.substring(1), 0) * 1000;
      return new Date(start.getTime() + offset);
    }

    SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyyMMddHHmmss");
    dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));
    return dateFormatter.parse(duration);
  }

  /**
   * Determine if the given keypairs can be used to sign the zone.
   * 
   * @param zonename the zone origin.
   * @param keypairs a list of {@link DnsKeyPair} objects that will be used to
   *          sign the zone.
   * @return true if the keypairs valid.
   */
  private static boolean keyPairsValidForZone(Name zonename, List keypairs)
  {
    if (keypairs == null) return true; // technically true, I guess.

    for (Iterator i = keypairs.iterator(); i.hasNext();)
    {
      DnsKeyPair kp = (DnsKeyPair) i.next();
      Name keyname = kp.getDNSKEYRecord().getName();
      if (!keyname.equals(zonename))
      {
        return false;
      }
    }

    return true;
  }

  /**
   * Conditionally sign an RRset and add it to the toList.
   * 
   * @param toList the list to which we are adding the processed RRsets.
   * @param zonename the zone apex name.
   * @param rrset the rrset under consideration.
   * @param keysigningkeypairs the List of KSKs..
   * @param zonekeypairs the List of zone keys.
   * @param start the RRSIG inception time.
   * @param expire the RRSIG expiration time.
   * @param fullySignKeyset if true, sign the zone apex keyset with both KSKs
   *          and ZSKs.
   * @param last_cut the name of the last delegation point encountered.
   * @return the name of the new last_cut.
   */
  private static Name addRRset(JCEDnsSecSigner signer, List toList,
      Name zonename, RRset rrset, List keysigningkeypairs, List zonekeypairs,
      Date start, Date expire, boolean fullySignKeyset, Name last_cut)
      throws IOException, GeneralSecurityException
  {
    // add the records themselves
    for (Iterator i = rrset.rrs(); i.hasNext();)
    {
      toList.add(i.next());
    }

    int type = SignUtils.recordSecType(zonename, rrset.getName(), rrset
        .getType(), last_cut);

    // we don't sign non-normal sets (delegations, glue, invalid).
    // we also don't sign the zone key set unless we've been asked.
    if (type == SignUtils.RR_DELEGATION)
    {
      return rrset.getName();
    }
    if (type == SignUtils.RR_GLUE || type == SignUtils.RR_INVALID)
    {
      return last_cut;
    }

    // check for the zone apex keyset.
    if (rrset.getName().equals(zonename) && rrset.getType() == Type.DNSKEY)
    {
      // if we have key signing keys, sign the keyset with them,
      // otherwise we will just sign them with the zonesigning keys.
      if (keysigningkeypairs != null && keysigningkeypairs.size() > 0)
      {
        List sigs = signer
            .signRRset(rrset, keysigningkeypairs, start, expire);
        toList.addAll(sigs);

        // If we aren't going to sign with all the keys, bail out now.
        if (!fullySignKeyset) return last_cut;
      }
    }

    // otherwise, we are OK to sign this set.
    List sigs = signer.signRRset(rrset, zonekeypairs, start, expire);
    toList.addAll(sigs);

    return last_cut;
  }

  /**
   * Given a zone, sign it.
   * 
   * @param signer A signer (utility) object to use to actually sign stuff.
   * @param zonename The name of the zone.
   * @param records The records comprising the zone. They do not have to be in
   *          any particular order, as this method will order them as
   *          necessary.
   * @param keysigningkeypairs The key pairs that are designated as "key
   *          signing keys".
   * @param zonekeypair This key pairs that are designated as "zone signing
   *          keys".
   * @param start The RRSIG inception time.
   * @param expire The RRSIG expiration time.
   * @param fullySignKeyset Sign the zone apex keyset with all available keys.
   * @param digest_id The digest identifier to use when generating DS records.
   * 
   * @return an ordered list of {@link org.xbill.DNS.Record} objects,
   *         representing the signed zone.
   */
  private static List signZone(JCEDnsSecSigner signer, Name zonename,
      List records, List keysigningkeypairs, List zonekeypairs, Date start,
      Date expire, boolean fullySignKeyset, int digest_id)
      throws IOException, GeneralSecurityException
  {

    // Remove any existing DNSSEC records (NSEC, NSEC3, RRSIG)
    SignUtils.removeGeneratedRecords(zonename, records);

    // Sort the zone
    Collections.sort(records, new RecordComparator());

    // Remove any duplicate records.
    SignUtils.removeDuplicateRecords(records);

    // Generate DS records
    SignUtils.generateDSRecords(zonename, records, digest_id);

    // Generate the NSEC records
    SignUtils.generateNSECRecords(zonename, records);

    // Assemble into RRsets and sign.
    RRset rrset = new RRset();
    ArrayList signed_records = new ArrayList();
    Name last_cut = null;

    for (ListIterator i = records.listIterator(); i.hasNext();)
    {
      Record r = (Record) i.next();

      // First record
      if (rrset.size() == 0)
      {
        rrset.addRR(r);
        continue;
      }

      // Current record is part of the current RRset.
      if (rrset.getName().equals(r.getName())
          && rrset.getDClass() == r.getDClass()
          && rrset.getType() == r.getType())
      {
        rrset.addRR(r);
        continue;
      }

      // Otherwise, we have completed the RRset
      // Sign the records

      // add the RRset to the list of signed_records, regardless of
      // whether or not we actually end up signing the set.
      last_cut = addRRset(signer,
          signed_records,
          zonename,
          rrset,
          keysigningkeypairs,
          zonekeypairs,
          start,
          expire,
          fullySignKeyset,
          last_cut);

      rrset.clear();
      rrset.addRR(r);
    }

    // add the last RR set
    addRRset(signer,
        signed_records,
        zonename,
        rrset,
        keysigningkeypairs,
        zonekeypairs,
        start,
        expire,
        fullySignKeyset,
        last_cut);

    return signed_records;
  }

  /**
   * Given a zone sign it using NSEC3 records.
   * 
   * @param signer A signer (utility) object used to actually sign stuff.
   * @param zonename The name of the zone being signed.
   * @param records The records comprising the zone. They do not have to be in
   *          any particular order, as this method will order them as
   *          necessary.
   * @param keysigningkeypairs The key pairs that are designated as "key
   *          signing keys".
   * @param zonekeypairs This key pairs that are designated as "zone signing
   *          keys".
   * @param start The RRSIG inception time.
   * @param expire The RRSIG expiration time.
   * @param fullySignKeyset If true then the DNSKEY RRset will be signed by
   *          all available keys, if false, only the key signing keys.
   * @param useOptOut If true, insecure delegations will be omitted from the
   *          NSEC3 chain, and all NSEC3 records will have the Opt-Out flag
   *          set.
   * @param includedNames A list of names to include in the NSEC3 chain
   *          regardless.
   * @param salt The salt to use for the NSEC3 hashing. null means no salt.
   * @param iterations The number of iterations to use for the NSEC3 hashing.
   * @param ds_digest_id The digest algorithm to use when generating DS
   *          records.
   * 
   * @return an ordered list of {@link org.xbill.DNS.Record} objects,
   *         representing the signed zone.
   * 
   * @throws IOException
   * @throws GeneralSecurityException
   */
  private static List signZoneNSEC3(JCEDnsSecSigner signer, Name zonename,
      List records, List keysigningkeypairs, List zonekeypairs, Date start,
      Date expire, boolean fullySignKeyset, boolean useOptOut,
      List includedNames, byte[] salt, int iterations, int ds_digest_id)
      throws IOException, GeneralSecurityException
  {
    // Remove any existing DNSSEC records (NSEC, NSEC3, NSEC3PARAM, RRSIG)
    SignUtils.removeGeneratedRecords(zonename, records);

    // Sort the zone
    Collections.sort(records, new RecordComparator());

    // Remove duplicate records
    SignUtils.removeDuplicateRecords(records);

    // Generate DS records
    SignUtils.generateDSRecords(zonename, records, ds_digest_id);

    // Generate NSEC3 records
    if (useOptOut)
    {
      SignUtils.generateOptOutNSEC3Records(zonename,
          records,
          includedNames,
          salt,
          iterations);
    }
    else
    {
      SignUtils.generateNSEC3Records(zonename, records, salt, iterations);
    }

    // Re-sort so we can assemble into rrsets.
    Collections.sort(records, new RecordComparator());

    // Assemble into RRsets and sign.
    RRset rrset = new RRset();
    ArrayList signed_records = new ArrayList();
    Name last_cut = null;

    for (ListIterator i = records.listIterator(); i.hasNext();)
    {
      Record r = (Record) i.next();

      // First record
      if (rrset.size() == 0)
      {
        rrset.addRR(r);
        continue;
      }

      // Current record is part of the current RRset.
      if (rrset.getName().equals(r.getName())
          && rrset.getDClass() == r.getDClass()
          && rrset.getType() == r.getType())
      {
        rrset.addRR(r);
        continue;
      }

      // Otherwise, we have completed the RRset
      // Sign the records

      // add the RRset to the list of signed_records, regardless of
      // whether or not we actually end up signing the set.
      last_cut = addRRset(signer,
          signed_records,
          zonename,
          rrset,
          keysigningkeypairs,
          zonekeypairs,
          start,
          expire,
          fullySignKeyset,
          last_cut);

      rrset.clear();
      rrset.addRR(r);
    }

    // add the last RR set
    addRRset(signer,
        signed_records,
        zonename,
        rrset,
        keysigningkeypairs,
        zonekeypairs,
        start,
        expire,
        fullySignKeyset,
        last_cut);

    return signed_records;
  }

  public static void execute(CLIState state) throws Exception
  {
    // Load the key pairs.

    // FIXME: should we do what BIND 9.3.x snapshots do and look at
    // zone apex DNSKEY RRs, and from that be able to load all of the
    // keys?

    List keypairs = getKeys(state.keyFiles, 0, state.keyDirectory);
    List kskpairs = getKeys(state.kskFiles, 0, state.keyDirectory);

    // If we don't have any KSKs, but we do have more than one zone
    // signing key (presumably), presume that the zone signing keys
    // are just not differentiated and try to figure out which keys
    // are actually ksks by looking at the SEP flag.
    if ((kskpairs == null || kskpairs.size() == 0) && keypairs != null
        && keypairs.size() > 1)
    {
      for (Iterator i = keypairs.iterator(); i.hasNext();)
      {
        DnsKeyPair pair = (DnsKeyPair) i.next();
        DNSKEYRecord kr = pair.getDNSKEYRecord();
        if ((kr.getFlags() & DNSKEYRecord.Flags.SEP_KEY) != 0)
        {
          if (kskpairs == null) kskpairs = new ArrayList();
          kskpairs.add(pair);
          i.remove();
        }
      }
    }

    // If there are no ZSKs defined at this point (yet there are KSKs
    // provided), all KSKs will be treated as ZSKs, as well.
    if (keypairs == null || keypairs.size() == 0)
    {
      keypairs = kskpairs;
    }

    // If there *still* aren't any ZSKs defined, bail.
    if (keypairs == null || keypairs.size() == 0)
    {
      System.err.println("No zone signing keys could be determined.");
      state.usage();
    }

    // Read in the zone
    List records = ZoneUtils.readZoneFile(state.zonefile, null);
    if (records == null || records.size() == 0)
    {
      System.err.println("error: empty zone file");
      state.usage();
    }

    // calculate the zone name.
    Name zonename = ZoneUtils.findZoneName(records);
    if (zonename == null)
    {
      System.err.println("error: invalid zone file - no SOA");
      state.usage();
    }

    // default the output file, if not set.
    if (state.outputfile == null)
    {
      if (zonename.isAbsolute())
      {
        state.outputfile = zonename + "signed";
      }
      else
      {
        state.outputfile = zonename + ".signed";
      }
    }

    // Verify that the keys can be in the zone.
    if (!keyPairsValidForZone(zonename, keypairs)
        || !keyPairsValidForZone(zonename, kskpairs))
    {
      System.err.println("error: specified keypairs are not valid for the zone.");
      state.usage();
    }

    // We force the signing keys to be in the zone by just appending
    // them to the zone here. Currently JCEDnsSecSigner.signZone
    // removes duplicate records.
    if (kskpairs != null)
    {
      for (Iterator i = kskpairs.iterator(); i.hasNext();)
      {
        records.add(((DnsKeyPair) i.next()).getDNSKEYRecord());
      }
    }
    if (keypairs != null)
    {
      for (Iterator i = keypairs.iterator(); i.hasNext();)
      {
        records.add(((DnsKeyPair) i.next()).getDNSKEYRecord());
      }
    }

    // read in the keysets, if any.
    List keysetrecs = getKeysets(state.keysetDirectory, zonename);
    if (keysetrecs != null)
    {
      records.addAll(keysetrecs);
    }

    JCEDnsSecSigner signer = new JCEDnsSecSigner();

    // Sign the zone.
    List signed_records;

    if (state.useNsec3)
    {
      signed_records = signZoneNSEC3(signer,
          zonename,
          records,
          kskpairs,
          keypairs,
          state.start,
          state.expire,
          state.fullySignKeyset,
          state.useOptOut,
          state.includeNames,
          state.salt,
          state.iterations,
          state.digest_id);
    }
    else
    {
      signed_records = signZone(signer,
          zonename,
          records,
          kskpairs,
          keypairs,
          state.start,
          state.expire,
          state.fullySignKeyset,
          state.digest_id);
    }

    // write out the signed zone
    // force multiline mode for now
    org.xbill.DNS.Options.set("multiline");
    ZoneUtils.writeZoneFile(signed_records, state.outputfile);

    if (state.verifySigs)
    {
      // FIXME: ugh.
      if (kskpairs != null)
      {
        keypairs.addAll(kskpairs);
      }

      log.fine("verifying generated signatures");
      boolean res = verifyZoneSigs(zonename, signed_records, keypairs);

      if (res)
      {
        System.out.println("Generated signatures verified");
        // log.info("Generated signatures verified");
      }
      else
      {
        System.out.println("Generated signatures did not verify.");
        // log.warn("Generated signatures did not verify.");
      }
    }

  }

  public static void main(String[] args)
  {
    CLIState state = new CLIState();
    try
    {
      state.parseCommandLine(args);
    }
    catch (UnrecognizedOptionException e)
    {
      System.err.println("error: unknown option encountered: "
          + e.getMessage());
      state.usage();
    }
    catch (AlreadySelectedException e)
    {
      System.err.println("error: mutually exclusive options have "
          + "been selected:\n     " + e.getMessage());
      state.usage();
    }
    catch (Exception e)
    {
      System.err.println("error: unknown command line parsing exception:");
      e.printStackTrace();
      state.usage();
    }

    log = Logger.getLogger(SignZone.class.toString());

    try
    {
      execute(state);
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }
  }
}
