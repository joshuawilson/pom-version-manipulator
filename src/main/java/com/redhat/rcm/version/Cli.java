/*
 * Copyright (c) 2012 Red Hat, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see
 * <http://www.gnu.org/licenses>.
 */

package com.redhat.rcm.version;

import static com.redhat.rcm.version.stats.VersionInfo.APP_BUILDER;
import static com.redhat.rcm.version.stats.VersionInfo.APP_COMMIT_ID;
import static com.redhat.rcm.version.stats.VersionInfo.APP_DESCRIPTION;
import static com.redhat.rcm.version.stats.VersionInfo.APP_NAME;
import static com.redhat.rcm.version.stats.VersionInfo.APP_TIMESTAMP;
import static com.redhat.rcm.version.stats.VersionInfo.APP_VERSION;
import static com.redhat.rcm.version.util.InputUtils.getClasspathResource;
import static com.redhat.rcm.version.util.InputUtils.getFile;
import static com.redhat.rcm.version.util.InputUtils.readClasspathProperties;
import static com.redhat.rcm.version.util.InputUtils.readListProperty;
import static com.redhat.rcm.version.util.InputUtils.readProperties;
import static com.redhat.rcm.version.util.InputUtils.readPropertiesList;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.lang.StringUtils.join;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.FileAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.spi.Configurator;
import org.apache.log4j.spi.LoggerRepository;
import org.apache.maven.mae.MAEException;
import org.apache.maven.mae.project.key.FullProjectKey;
import org.codehaus.plexus.util.StringUtils;
import org.commonjava.util.logging.Logger;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.redhat.rcm.version.mgr.VersionManager;
import com.redhat.rcm.version.mgr.mod.ProjectModder;
import com.redhat.rcm.version.mgr.session.SessionBuilder;
import com.redhat.rcm.version.mgr.session.VersionManagerSession;
import com.redhat.rcm.version.util.InputUtils;
import com.redhat.rcm.version.util.http.SSLUtils;

public class Cli
{
    @Argument( index = 0, metaVar = "target", usage = "POM file (or directory containing POM files) to modify." )
    private File target = new File( System.getProperty( "user.dir" ), "pom.xml" );

    @Option( name = "-b", aliases = "--boms", usage = "File containing a list of BOM URLs to use for standardizing dependencies.\nProperty file equivalent: boms" )
    private File bomList;

    @Option( name = "-B", aliases = { "--bootstrap" }, usage = "Bootstrap properties to read for location of VMan configuration." )
    private File bootstrapConfig;

    @Option( name = "-C", aliases = "--config", usage = "Load default configuration for BOMs, toolchain, removedPluginsList, etc. from this file/url." )
    private String configuration;

    @Option( name = "-e", usage = "POM exclude path pattern (glob)" )
    private String pomExcludePattern;

    @Option( name = "-E", usage = "POM exclude module list (groupId:artifactId,groupId:artifactId...)\nProperty file equivalent: pom-module-excludes" )
    private String pomExcludeModules;

    @Option( name = "-h", aliases = { "--help" }, usage = "Print this message and quit" )
    private boolean help;

    @Option( name = "-H", aliases = { "--help-modifications" }, usage = "Print the list of available modifications and quit" )
    private boolean helpModders;

    @Option( name = "--console", usage = "Log information to console IN ADDITION TO <workspace>/vman.log.\n" )
    private boolean console;

    @Option( name = "--no-console", usage = "DON'T log information to console in addition to <workspace>/vman.log.\n" )
    private boolean noConsole;

    @Option( name = "--no-log-file", usage = "DON'T log information to <workspace>/vman.log.\n" )
    private boolean noLogFile;

    @Option( name = "-L", aliases = { "--local-repo", "--local-repository" }, usage = "Local repository directory.\nDefault: <workspace>/local-repository\nProperty file equivalent: local-repository" )
    private File localRepository;

    @Option( name = "-m", aliases = "--remote-repositories", usage = "Maven remote repositories from which load missing parent POMs.\nProperty file equivalent: remote-repositories." )
    private String remoteRepositories;

    @Option( name = "-M", aliases = { "--enable-modifications" }, usage = "List of modifications to enable for this execution (see --help-modifications for more information)." )
    private String modifications;

    @Option( name = "-O", aliases = { "--capture-output", "--capture-pom" }, usage = "Write captured (missing) definitions to this POM location.\nProperty file equivalent: capture-pom" )
    private File capturePom;

    @Option( name = "-p", usage = "POM path pattern (glob).\nDefault: **/*.pom,**/pom.xml" )
    private final String pomPattern = "**/*.pom,**/pom.xml";

    @Option( name = "-P", aliases = { "--preserve" }, usage = "Write changed POMs back to original input files.\nDefault: false" )
    private final boolean preserveFiles = false;

    @Option( name = "-r", aliases = { "--rm-plugins", "--removed-plugins" }, usage = "List of plugins (format: <groupId:artifactId>[,<groupId:artifactId>]) to REMOVE if found.\nProperty file equivalent: removed-plugins" )
    private String removedPluginsList;

    @Option( name = "--removed-tests", usage = "List of test modules (format: <groupId:artifactId>[,<groupId:artifactId>]) to remove (via maven.test.skip) if found.\nProperty file equivalent: removed-tests" )
    private String removedTestsList;

    @Option( name = "--extensions-whitelist", usage = "List of extensions (format: <groupId:artifactId>[,<groupId:artifactId>]) to preserve.\nProperty file equivalent: extensions-whitelist" )
    private String extensionsWhitelistList;

    @Option( name = "-R", aliases = { "--report-dir" }, usage = "Write reports here.\nDefault: <workspace>/reports" )
    private final File reports = new File( "vman-workspace/reports" );

    @Option( name = "-s", aliases = "--version-suffix", usage = "A suffix to append to each POM's version.\nProperty file equivalent: version-suffix" )
    private String versionSuffix;

    @Option( name = "--version-modifier", usage = "Change each POM's version using pattern:replacement format.\nProperty file equivalent: version-modifier" )
    private String versionModifier;

    @Option( name = "--strict", usage = "Change ONLY the dependencies, plugins, and parents that are listed in BOMs and toolchain POM\nDefault: false\nProperty file equivalent: strict" )
    private boolean strict = false;

    @Option( name = "-S", aliases = { "--settings" }, usage = "Maven settings.xml file/URL.\nProperty file equivalent: settings" )
    private String settings;

    @Option( name = "-t", aliases = "--toolchain", usage = "Toolchain POM URL, containing standard plugin versions in the build/pluginManagement section, and plugin injections in the regular build/plugins section.\nProperty file equivalent: toolchain" )
    private String toolchain;

    @Option( name = "-T", aliases = "--test-config", usage = "Test-load the configuration given, and print diagnostic information" )
    private boolean testConfig;

    @Option( name = "-v", aliases = "--version", usage = "Show version information and quit." )
    private boolean showVersion;

    @Option( name = "-W", aliases = { "--workspace" }, usage = "Backup original files here up before modifying.\nDefault: vman-workspace" )
    private final File workspace = new File( "vman-workspace" );

    @Option( name = "-Z", aliases = { "--no-system-exit" }, usage = "Don't call System.exit(..) with the return value (for embedding/testing)." )
    private boolean noSystemExit;

    @Option( name = "--trustpath", usage = "Directory containing .pem files with certificates of servers to trust. (Use 'classpath:' prefix for a directory embedded in the jar.)" )
    private String truststorePath = DEFAULT_TRUSTSTORE_PATH;

    @Option( name = "--use-effective-poms", usage = "Disable resolution of effective POMs for projects being modified (May be useful if parent POMs aren't resolvable)." )
    private boolean useEffectivePoms = false;

    private static final String DEFAULT_TRUSTSTORE_PATH = "classpath:ssl/trust";

    private static final File DEFAULT_CONFIG_FILE = new File( System.getProperty( "user.home" ), ".vman.properties" );

    static final String BOOTSTRAP_PROPERTIES = "vman.boot.properties";

    public static final String REMOTE_REPOSITORIES_PROPERTY = "remote-repositories";

    @Deprecated
    public static final String REMOTE_REPOSITORY_PROPERTY = "remote-repository";

    public static final String VERSION_SUFFIX_PROPERTY = "version-suffix";

    public static final String VERSION_MODIFIER_PROPERTY = "version-modifier";

    public static final String TOOLCHAIN_PROPERTY = "toolchain";

    public static final String BOMS_LIST_PROPERTY = "boms";

    public static final String REMOVED_PLUGINS_PROPERTY = "removed-plugins";

    public static final String EXTENSIONS_WHITELIST_PROPERTY = "extensions-whitelist";

    public static final String POM_EXCLUDE_MODULE_PROPERTY = "pom-module-excludes";

    public static final String REMOVED_TESTS_PROPERTY = "removed-tests";

    public static final String LOCAL_REPOSITORY_PROPERTY = "local-repository";

    public static final String SETTINGS_PROPERTY = "settings";

    public static final String CAPTURE_POM_PROPERTY = "capture-pom";

    public static final String STRICT_MODE_PROPERTY = "strict";

    public static final String MODIFICATIONS = "modifications";

    public static final String RELOCATIONS_PROPERTY = "relocated-coordinates";

    public static final String PROPERTY_MAPPINGS_PROPERTY = "property-mappings";

    public static final String BOOT_CONFIG_PROPERTY = "configuration";

    public static final String TRUSTSTORE_PATH_PROPERTY = "truststore-path";

    public static final String USE_EFFECTIVE_POMS_PROPERTY = "use-effective-poms";

    private static final File DEFAULT_BOOTSTRAP_CONFIG = new File( System.getProperty( "user.home" ),
                                                                   ".vman.boot.properties" );

    private static VersionManager vman;

    private List<String> boms;

    private List<String> removedPlugins;

    private List<String> extensionsWhitelist;

    private List<String> removedTests;

    private List<String> modders;

    private Map<String, String> relocatedCoords;

    private Map<String, String> propertyMappings;

    private String bootstrapLocation;

    private String configLocation;

    private boolean bootstrapRead;

    private final File logFile = new File( workspace, "vman.log" );

    private static int exitValue = Integer.MIN_VALUE;

    public static void main( final String[] args )
    {
        final Cli cli = new Cli();
        final CmdLineParser parser = new CmdLineParser( cli );
        try
        {
            parser.parseArgument( args );

            final boolean useLog =
                !( cli.noLogFile || cli.testConfig || /*cli.help ||*/cli.helpModders || cli.showVersion );

            //            System.out.printf( "--no-console: %s \n\n--no-log-file: %s \n--test-config: %s\n--help: %s\n--help-modifications: %s\n--version: %s\nlogfile: %s\n\nUse logfile? %s\n\n",
            //                               cli.noConsole, cli.noLogFile, cli.testConfig, cli.help, cli.helpModders,
            //                               cli.showVersion, cli.logFile, useLog );

            configureLogging( !cli.noConsole, useLog, cli.logFile );
            new Logger( Cli.class ).info( "Testing log appenders..." );

            vman = VersionManager.getInstance();

            exitValue = 0;
            if ( cli.help )
            {
                printUsage( parser, null );
            }
            else if ( cli.helpModders )
            {
                printModders();
            }
            else if ( cli.showVersion )
            {
                printVersionInfo();
            }
            else if ( cli.testConfig )
            {
                cli.testConfigAndPrintDiags();
            }
            else
            {
                exitValue = cli.run();
            }

            if ( !cli.noSystemExit )
            {
                System.exit( exitValue );
            }
        }
        catch ( final CmdLineException error )
        {
            printUsage( parser, error );
        }
        catch ( final MAEException e )
        {
            printUsage( parser, e );
        }
        catch ( final MalformedURLException e )
        {
            printUsage( parser, e );
        }
    }

    public Cli( final File target, final File bomList )
    {
        this.target = target;
        this.bomList = bomList;
    }

    public Cli()
    {
    }

    private void testConfigAndPrintDiags()
    {
        VersionManagerSession session = null;
        final List<VManException> errors = new ArrayList<VManException>();
        try
        {
            session = initSession();
        }
        catch ( final VManException e )
        {
            errors.add( e );
        }

        if ( session != null )
        {
            try
            {
                vman.configureSession( boms, toolchain, session );
            }
            catch ( final VManException e )
            {
                errors.add( e );
            }
        }

        final FullProjectKey toolchainKey = session == null ? null : session.getToolchainKey();
        final List<FullProjectKey> bomCoords = session == null ? null : session.getBomCoords();

        final LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put( "Bootstrap location:", bootstrapLocation );
        map.put( "Bootstrap read?", bootstrapRead );
        map.put( "Config location:", configLocation );
        map.put( "", "" );
        map.put( " ", "" );
        map.put( "Toolchain location:", toolchain );
        map.put( "Toolchain:", toolchainKey );
        map.put( "    ", "" );
        map.put( "BOM locations:", boms );
        map.put( "BOMs:", bomCoords );
        map.put( "  ", "" );
        map.put( "   ", "" );
        map.put( "Settings.xml:", settings );
        map.put( "Remote repo:", remoteRepositories );

        System.out.println( "Version information:\n-------------------------------------------------\n\n" );
        printVersionInfo();
        System.out.printf( "Diagnostics:\n-------------------------------------------------\n\n" );
        int max = 0;
        for ( final String key : map.keySet() )
        {
            max = Math.max( max, key.length() );
        }

        final StringBuilder indent = new StringBuilder();
        for ( int i = 0; i < max + 4; i++ )
        {
            indent.append( ' ' );
        }

        final int descMax = 75 - max;
        final String fmt = "%-" + max + "s    %-" + descMax + "s\n";
        for ( final Map.Entry<String, Object> entry : map.entrySet() )
        {
            final Object value = entry.getValue();

            String val = value == null ? "-NONE-" : String.valueOf( value );
            if ( value instanceof Collection<?> )
            {
                final Collection<?> coll = ( (Collection<?>) value );
                if ( coll.isEmpty() )
                {
                    val = "-NONE-";
                }
                else
                {
                    val = join( coll, "\n" + indent ) + "\n";
                }
            }

            System.out.printf( fmt, entry.getKey(), val );
        }

        System.out.println();
        System.out.printf( "Errors:\n-------------------------------------------------\n%s\n\n",
                           errors.isEmpty() ? "-NONE" : join( errors, "\n\n" ) );
        System.out.println();
    }

    private static void configureLogging( boolean useConsole, final boolean useLogFile, final File logFile )
    {
        System.out.println( "Log file is: " + logFile.getAbsolutePath() );

        final Layout layout = new PatternLayout( "%5p [%t] - %m%n" );

        final List<AppenderSkeleton> appenders = new ArrayList<AppenderSkeleton>();
        if ( !useConsole && !useLogFile )
        {
            if ( !useLogFile )
            {
                System.out.println( "\n\nNOTE: --no-console option has been OVERRIDDEN since --no-log-file option was also provided.\nOutputting to console ONLY.\n" );
                useConsole = true;
            }
        }

        if ( useConsole )
        {
            final ConsoleAppender console = new ConsoleAppender( layout );
            console.setName( "console" );
            console.setThreshold( Level.ALL );

            appenders.add( console );
        }

        if ( useLogFile )
        {
            System.out.println( "\n\nNOTE: See " + logFile + " for a COPY of console output.\n" );

            try
            {
                final File dir = logFile.getParentFile();
                if ( dir != null && !dir.isDirectory() && !dir.mkdirs() )
                {
                    throw new RuntimeException( "Failed to create parent directory for logfile: "
                        + dir.getAbsolutePath() );
                }

                final FileAppender file = new FileAppender( layout, logFile.getPath() );
                file.setName( "logfile" );
                file.setThreshold( Level.ALL );

                appenders.add( file );
            }
            catch ( final IOException e )
            {
                final StringWriter sw = new StringWriter();
                final PrintWriter pw = new PrintWriter( sw );
                e.printStackTrace( pw );
                System.out.printf( "ERROR: Failed to initialize log file: %s. Reason: %s\n\n%s\n\n", logFile,
                                   e.getMessage(), sw.toString() );

                throw new RuntimeException( "Failed to initialize logfile." );
            }
        }

        // Clear the logfile for the next run.
        if ( logFile != null )
        {
            logFile.delete();
        }

        final Configurator log4jConfigurator = new Configurator()
        {
            @Override
            public void doConfigure( final URL notUsed, final LoggerRepository repo )
            {
                final Level level = Level.INFO;

                repo.setThreshold( level );

                final org.apache.log4j.Logger root = repo.getRootLogger();
                root.removeAllAppenders();

                root.setLevel( level );

                for ( final AppenderSkeleton appender : appenders )
                {
                    appender.setThreshold( Level.ALL );
                    root.addAppender( appender );
                }

                @SuppressWarnings( "unchecked" )
                final ArrayList<Appender> allRoot = Collections.list( root.getAllAppenders() );
                for ( final Appender appender : allRoot )
                {
                    System.out.println( "ROOT has appender: " + appender.getName() );
                }

            }
        };

        log4jConfigurator.doConfigure( null, LogManager.getLoggerRepository() );
    }

    public int run()
        throws MAEException, VManException, MalformedURLException
    {
        final Logger logger = new Logger( getClass() );

        final VersionManagerSession session = initSession();

        if ( boms == null || boms.isEmpty() )
        {
            logger.error( "You must specify at least one BOM." );
            return -2;
        }

        if ( session.getErrors()
                    .isEmpty() )
        {
            logger.info( "Modifying POM(s).\n\nTarget:\n\t" + target + "\n\nBOMs:\n\t"
                + StringUtils.join( boms.iterator(), "\n\t" ) + "\n\nWorkspace:\n\t" + workspace + "\n\nReports:\n\t"
                + reports );

            if ( target.isDirectory() )
            {
                vman.modifyVersions( target, pomPattern, pomExcludePattern, boms, toolchain, session );
            }
            else
            {
                vman.modifyVersions( target, boms, toolchain, session );
            }
        }

        reports.mkdirs();
        vman.generateReports( reports, session );

        if ( capturePom != null && capturePom.exists() )
        {
            logger.warn( "\n\n\n\n\nMissing dependency/plugin information has been captured in:\n\n\t"
                + capturePom.getAbsolutePath() + "\n\n\n\n" );

            return -1;
        }
        else
        {
            final List<Throwable> errors = session.getErrors();
            if ( errors != null && !errors.isEmpty() )
            {
                logger.error( errors.size() + " errors detected!\n\n" );

                int i = 1;
                for ( final Throwable error : errors )
                {
                    logger.error( "\n\n" + i, error );
                    i++;
                }

                return -1;
            }
        }

        return 0;
    }

    private VersionManagerSession initSession()
        throws VManException
    {
        SSLUtils.initSSLContext( truststorePath );

        loadConfiguration();

        loadBomList();

        loadPlugins();

        loadAndNormalizeModifications();

        final Logger logger = new Logger( getClass() );
        logger.info( "modifications = " + join( modders, " " ) );

        final SessionBuilder builder =
            new SessionBuilder( workspace, reports ).withVersionSuffix( versionSuffix )
                                                    .withVersionModifier( versionModifier )
                                                    .withRemovedPlugins( removedPlugins )
                                                    .withRemovedTests( removedTests )
                                                    .withExtensionsWhitelist( extensionsWhitelist )
                                                    .withModders( modders )
                                                    .withPreserveFiles( preserveFiles )
                                                    .withStrict( strict )
                                                    .withCoordinateRelocations( relocatedCoords )
                                                    .withPropertyMappings( propertyMappings )
                                                    .withExcludedModulePoms( pomExcludeModules )
                                                    .withUseEffectivePoms( useEffectivePoms );

        final VersionManagerSession session = builder.build();

        if ( remoteRepositories != null )
        {
            try
            {
                session.setRemoteRepositories( remoteRepositories );
            }
            catch ( final MalformedURLException e )
            {
                throw new VManException( "Cannot initialize remote repositories: %s. Error: %s", e, remoteRepositories,
                                         e.getMessage() );
            }
        }

        if ( settings != null )
        {
            session.setSettingsXml( settings );
        }

        if ( localRepository == null )
        {
            localRepository = new File( workspace, "local-repository" );
        }

        session.setLocalRepositoryDirectory( localRepository );

        if ( capturePom != null )
        {
            session.setCapturePom( capturePom );
        }

        return session;
    }

    private static void printVersionInfo()
    {
        final StringBuilder sb = new StringBuilder();
        sb.append( APP_NAME )
          .append( "\n\n" )
          .append( APP_DESCRIPTION )
          .append( "\n\n" );

        final LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        map.put( "Built By:", APP_BUILDER );
        map.put( "Commit ID:", APP_COMMIT_ID );
        map.put( "Built On:", APP_TIMESTAMP );
        map.put( "Version:", APP_VERSION );

        sb.append( formatHelpMap( map, "\n" ) );
        sb.append( "\n\n" );

        System.out.println( sb.toString() );
    }

    private static void printModders()
    {
        final Map<String, ProjectModder> modders = vman.getModders();

        final List<String> keys = new ArrayList<String>( modders.keySet() );
        Collections.sort( keys );

        final LinkedHashMap<String, Object> props = new LinkedHashMap<String, Object>();
        for ( final String key : keys )
        {
            props.put( key, modders.get( key )
                                   .getDescription() );
        }

        final StringBuilder sb = new StringBuilder();
        sb.append( "The following project modifications are available: " );
        sb.append( formatHelpMap( props, "\n\n" ) );

        sb.append( "\n\nNOTE: To ADD any of these modifiers to the standard list, use the notation '--modifications=+<modifier-id>' (prefixed with '+') or for the properties file use 'modifications=+...'.\n\nThe standard modifiers are: " );

        for ( final String key : ProjectModder.STANDARD_MODIFICATIONS )
        {
            sb.append( String.format( "\n  - %s", key ) );
        }

        sb.append( "\n\n" );

        System.out.println( sb );
    }

    private static String formatHelpMap( final LinkedHashMap<String, Object> map, final String itemSeparator )
    {
        int max = 0;
        for ( final String key : map.keySet() )
        {
            max = Math.max( max, key.length() );
        }

        final int descMax = 75 - max;
        final String fmt = "%-" + max + "s    %-" + descMax + "s" + itemSeparator;

        final StringWriter sw = new StringWriter();
        final PrintWriter pw = new PrintWriter( sw );

        final List<String> lines = new ArrayList<String>();
        for ( final Map.Entry<String, Object> entry : map.entrySet() )
        {
            final String key = entry.getKey();
            final String description = entry.getValue() == null ? "-NONE-" : String.valueOf( entry.getValue() );

            lines.clear();
            final BreakIterator iter = BreakIterator.getLineInstance();
            iter.setText( description );

            int start = iter.first();
            int end = BreakIterator.DONE;
            final StringBuilder currentLine = new StringBuilder();
            String seg;
            while ( start != BreakIterator.DONE && ( end = iter.next() ) != BreakIterator.DONE )
            {
                seg = description.substring( start, end );
                if ( currentLine.length() + seg.length() > descMax )
                {
                    lines.add( currentLine.toString() );
                    currentLine.setLength( 0 );
                }

                currentLine.append( seg );
                start = end;
            }

            if ( currentLine.length() > 0 )
            {
                lines.add( currentLine.toString() );
            }

            pw.printf( fmt, key, lines.isEmpty() ? "" : lines.get( 0 ) );
            if ( lines.size() > 1 )
            {
                for ( int i = 1; i < lines.size(); i++ )
                {
                    pw.printf( fmt, "", lines.get( i ) );
                }
            }
        }

        return sw.toString();
    }

    private void loadPlugins()
    {
        if ( extensionsWhitelistList == null && extensionsWhitelistList != null )
        {
            final String[] ls = extensionsWhitelistList.split( "\\s*,\\s*" );
            extensionsWhitelist = Arrays.asList( ls );
        }
        if ( removedPlugins == null && removedPluginsList != null )
        {
            final String[] ls = removedPluginsList.split( "\\s*,\\s*" );
            removedPlugins = Arrays.asList( ls );
        }
        if ( removedTests == null && removedTestsList != null )
        {
            final String[] ls = removedTestsList.split( "\\s*,\\s*" );
            removedTests = Arrays.asList( ls );
        }
    }

    private void loadAndNormalizeModifications()
    {
        if ( modifications != null )
        {
            final String[] ls = modifications.split( "\\s*,\\s*" );
            modders = new ArrayList<String>();
            for ( final String modder : ls )
            {
                if ( !modders.contains( modder ) )
                {
                    modders.add( modder );
                }
            }
        }

        final List<String> mods = new ArrayList<String>();
        boolean loadStandards = modders == null;
        if ( modders != null )
        {
            if ( !modders.isEmpty() && modders.iterator()
                                              .next()
                                              .startsWith( "+" ) )
            {
                loadStandards = true;
            }

            for ( final String key : modders )
            {
                if ( ProjectModder.STANDARD_MODS_ALIAS.equals( key ) )
                {
                    loadStandards = true;
                }
                else if ( key.startsWith( "+" ) )
                {
                    if ( key.length() > 1 )
                    {
                        mods.add( key.substring( 1 )
                                     .trim() );
                    }
                }
                else
                {
                    mods.add( key );
                }
            }
        }

        if ( loadStandards )
        {
            mods.addAll( Arrays.asList( ProjectModder.STANDARD_MODIFICATIONS ) );
        }

        modders = mods;
    }

    private void loadConfiguration()
        throws VManException
    {
        final Logger logger = new Logger( getClass() );

        File config = null;

        if ( configuration != null )
        {
            config = InputUtils.getFile( configuration, workspace );
        }

        if ( config == null )
        {
            config = loadBootstrapConfig();
        }

        if ( config == null )
        {
            configLocation = DEFAULT_CONFIG_FILE.getAbsolutePath();
            config = DEFAULT_CONFIG_FILE;
        }

        if ( config != null && config.canRead() )
        {
            InputStream is = null;
            try
            {
                is = new FileInputStream( config );
                final Properties props = new Properties();
                props.load( is );

                final StringWriter sWriter = new StringWriter();
                for ( final Enumeration<?> e = props.propertyNames(); e.hasMoreElements(); )
                {
                    final String key = (String) e.nextElement();
                    sWriter.write( "  " );
                    sWriter.write( key );
                    sWriter.write( " = " );
                    sWriter.write( props.getProperty( key ) );
                    sWriter.write( "\n" );
                }

                props.list( new PrintWriter( sWriter ) );

                logger.info( "Loading configuration from: " + config + ":\n\n" + sWriter );

                final File downloadsDir = VersionManagerSession.getDownloadsDir( workspace );
                final List<String> relocations = readListProperty( props, RELOCATIONS_PROPERTY );
                if ( relocations != null )
                {
                    relocatedCoords = readPropertiesList( relocations, downloadsDir, true );
                }
                else
                {
                    relocatedCoords = new HashMap<String, String>();
                }

                final List<String> mappingsLocations = readListProperty( props, PROPERTY_MAPPINGS_PROPERTY );
                if ( propertyMappings != null )
                {
                    this.propertyMappings = readPropertiesList( mappingsLocations, downloadsDir, true );
                }
                else
                {
                    this.propertyMappings = new HashMap<String, String>();
                }

                if ( removedPluginsList == null )
                {
                    removedPlugins = readListProperty( props, REMOVED_PLUGINS_PROPERTY );
                }

                if ( removedTestsList == null )
                {
                    removedTests = readListProperty( props, REMOVED_TESTS_PROPERTY );
                }

                if ( extensionsWhitelistList == null )
                {
                    extensionsWhitelist = readListProperty( props, EXTENSIONS_WHITELIST_PROPERTY );
                }

                if ( pomExcludeModules == null )
                {
                    pomExcludeModules = props.getProperty( POM_EXCLUDE_MODULE_PROPERTY );
                }

                if ( modifications == null )
                {
                    final List<String> lst = readListProperty( props, MODIFICATIONS );
                    logger.info( "modifications from properties: '" + join( lst, " " ) + "'" );
                    if ( lst != null )
                    {
                        modders = modders == null ? new ArrayList<String>() : new ArrayList<String>( modders );
                        modders.addAll( lst );
                    }
                }

                if ( bomList == null )
                {
                    if ( boms == null )
                    {
                        boms = new ArrayList<String>();
                    }

                    final List<String> pBoms = readListProperty( props, BOMS_LIST_PROPERTY );
                    if ( pBoms != null )
                    {
                        boms.addAll( pBoms );
                    }
                }

                if ( toolchain == null )
                {
                    toolchain = props.getProperty( TOOLCHAIN_PROPERTY );
                    if ( toolchain != null )
                    {
                        toolchain = toolchain.trim();
                    }
                }

                if ( versionSuffix == null )
                {
                    versionSuffix = props.getProperty( VERSION_SUFFIX_PROPERTY );
                    if ( versionSuffix != null )
                    {
                        versionSuffix = versionSuffix.trim();
                    }
                }

                if ( versionModifier == null )
                {
                    versionModifier = props.getProperty( VERSION_MODIFIER_PROPERTY );
                    if ( versionModifier != null )
                    {
                        versionModifier = versionModifier.trim();
                    }
                }

                if ( remoteRepositories == null )
                {
                    remoteRepositories = props.getProperty( REMOTE_REPOSITORIES_PROPERTY );
                    if ( remoteRepositories != null )
                    {
                        remoteRepositories = remoteRepositories.trim();
                    }
                    else
                    {
                        remoteRepositories = props.getProperty( REMOTE_REPOSITORY_PROPERTY );

                        if ( remoteRepositories != null )
                        {
                            logger.warn( "Using deprecated " + REMOTE_REPOSITORY_PROPERTY );
                            remoteRepositories = remoteRepositories.trim();
                        }
                    }
                }

                if ( settings == null )
                {
                    final String s = props.getProperty( SETTINGS_PROPERTY );
                    if ( s != null )
                    {
                        settings = s;
                    }
                }

                if ( localRepository == null )
                {
                    final String l = props.getProperty( LOCAL_REPOSITORY_PROPERTY );
                    if ( l != null )
                    {
                        localRepository = new File( l );
                    }
                }

                if ( capturePom == null )
                {
                    final String p = props.getProperty( CAPTURE_POM_PROPERTY );
                    if ( p != null )
                    {
                        capturePom = new File( p );
                    }
                }

                if ( !strict )
                {
                    strict =
                        Boolean.valueOf( props.getProperty( STRICT_MODE_PROPERTY, Boolean.toString( Boolean.FALSE ) ) );
                }

                if ( !useEffectivePoms )
                {
                    useEffectivePoms =
                        Boolean.valueOf( props.getProperty( USE_EFFECTIVE_POMS_PROPERTY,
                                                            Boolean.toString( Boolean.FALSE ) ) );
                }

                if ( truststorePath == null )
                {
                    truststorePath = props.getProperty( TRUSTSTORE_PATH_PROPERTY );
                }
            }
            catch ( final IOException e )
            {
                throw new VManException( "Failed to load configuration from: " + config, e );
            }
            finally
            {
                closeQuietly( is );
            }
        }
        else
        {
            configLocation = "command-line";
        }
    }

    /**
     * Try to load bootstrap configuration using the following order or preference:
     * 1. configured file (using -B option)
     * 2. default file ($HOME/.vman.boot.properties)
     * 3. embedded resource (classpath:bootstrap.properties)
     *
     * @return The configuration file referenced by the bootstrap properties, or null if no bootstrap properties is
     *         found.
     *
     * @throws VManException In cases where the specified bootstrap properties file is unreadable.
     */
    private File loadBootstrapConfig()
        throws VManException
    {
        final Logger logger = new Logger( getClass() );

        Map<String, String> bootProps = null;
        if ( bootstrapConfig == null )
        {
            if ( DEFAULT_BOOTSTRAP_CONFIG.exists() && DEFAULT_BOOTSTRAP_CONFIG.canRead() )
            {
                logger.info( "Reading bootstrap info from: " + DEFAULT_BOOTSTRAP_CONFIG );
                bootstrapLocation = "file:" + DEFAULT_BOOTSTRAP_CONFIG.getAbsolutePath();

                bootProps = readProperties( DEFAULT_BOOTSTRAP_CONFIG );
            }
            else
            {
                logger.info( "Reading bootstrap info from classpath resource: " + BOOTSTRAP_PROPERTIES );
                final URL resource = getClasspathResource( BOOTSTRAP_PROPERTIES );
                if ( resource != null )
                {
                    bootstrapLocation = "classpath:" + resource;

                    bootProps = readClasspathProperties( BOOTSTRAP_PROPERTIES );
                }
            }
        }
        else
        {
            if ( !bootstrapConfig.exists() || !bootstrapConfig.canRead() )
            {
                throw new VManException( "Cannot read bootstrap from: " + bootstrapConfig );
            }
            else
            {
                logger.info( "Reading bootstrap info from: " + bootstrapConfig );
                bootstrapLocation = "file:" + bootstrapConfig.getAbsolutePath();

                bootProps = readProperties( bootstrapConfig );
            }
        }

        bootstrapRead = bootProps != null;

        if ( bootProps != null )
        {
            configLocation = bootProps.get( BOOT_CONFIG_PROPERTY );
            if ( configLocation != null )
            {
                logger.info( "Reading configuration from: " + configLocation );
                try
                {
                    final File file =
                        getFile( configLocation, new File( System.getProperty( "java.io.tmpdir" ) ), true );

                    logger.info( "...downloaded to file: " + file );
                    return file;
                }
                catch ( final VManException e )
                {
                    logger.error( "Failed to download configuration from: " + configLocation + ". Reason: "
                                      + e.getMessage(), e );
                    throw e;
                }
            }
        }

        return null;
    }

    private void loadBomList()
        throws VManException
    {
        if ( boms == null )
        {
            boms = new ArrayList<String>();
        }

        if ( bomList != null && bomList.canRead() )
        {
            BufferedReader reader = null;
            try
            {
                reader = new BufferedReader( new FileReader( bomList ) );
                String line = null;
                while ( ( line = reader.readLine() ) != null )
                {
                    boms.add( line.trim() );
                }
            }
            catch ( final IOException e )
            {
                throw new VManException( "Failed to read bom list from: " + bomList, e );
            }
            finally
            {
                closeQuietly( reader );
            }
        }
    }

    private static void printUsage( final CmdLineParser parser, final Exception error )
    {
        if ( error != null )
        {
            System.err.println( "Invalid option(s): " + error.getMessage() );
            System.err.println();
        }

        System.err.println( "Usage: $0 [OPTIONS] [<target-path>]" );
        System.err.println();
        System.err.println();
        // If we are running under a Linux shell COLUMNS might be available for the width
        // of the terminal.
        parser.setUsageWidth( ( System.getenv( "COLUMNS" ) == null ? 100 : Integer.valueOf( System.getenv( "COLUMNS" ) ) ) );
        parser.printUsage( System.err );
        System.err.println();
    }
}
