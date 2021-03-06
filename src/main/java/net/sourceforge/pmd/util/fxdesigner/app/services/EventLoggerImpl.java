/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.util.fxdesigner.app.services;

import static net.sourceforge.pmd.util.fxdesigner.app.services.LogEntry.Category.PARSE_EXCEPTION;
import static net.sourceforge.pmd.util.fxdesigner.app.services.LogEntry.Category.PARSE_OK;
import static net.sourceforge.pmd.util.fxdesigner.app.services.LogEntry.Category.SELECTION_EVENT_TRACING;
import static net.sourceforge.pmd.util.fxdesigner.app.services.LogEntry.Category.XPATH_EVALUATION_EXCEPTION;
import static net.sourceforge.pmd.util.fxdesigner.app.services.LogEntry.Category.XPATH_OK;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;

import org.reactfx.EventSource;
import org.reactfx.EventStream;
import org.reactfx.EventStreams;
import org.reactfx.collection.LiveArrayList;
import org.reactfx.collection.LiveList;

import net.sourceforge.pmd.util.fxdesigner.app.ApplicationComponent;
import net.sourceforge.pmd.util.fxdesigner.app.DesignerRoot;
import net.sourceforge.pmd.util.fxdesigner.app.services.LogEntry.Category;
import net.sourceforge.pmd.util.fxdesigner.app.services.LogEntry.LogEntryWithData;
import net.sourceforge.pmd.util.fxdesigner.util.reactfx.ReactfxUtil;
import net.sourceforge.pmd.util.fxdesigner.util.reactfx.VetoableEventStream;


/**
 * Logs events. Stores the whole log in case no view was open.
 *
 * @author Clément Fournier
 * @since 6.0.0
 */
public class EventLoggerImpl implements ApplicationComponent, EventLogger {

    /**
     * Exceptions from XPath evaluation or parsing are never emitted
     * within less than that time interval to keep them from flooding the tableview.
     */
    private static final Duration PARSE_EXCEPTION_REDUCTION_DELAY = Duration.ofMillis(3000);
    private static final Duration EVENT_TRACING_REDUCTION_DELAY = Duration.ofMillis(200);
    private final EventSource<LogEntry> latestEvent = new EventSource<>();
    private final LiveList<LogEntry> fullLog = new LiveArrayList<>();
    private final DesignerRoot designerRoot;


    public EventLoggerImpl(DesignerRoot designerRoot) {
        this.designerRoot = designerRoot; // we have to be careful with initialization order here

        EventStream<LogEntry> onlyParseException = deleteOnSignal(latestEvent, PARSE_EXCEPTION, PARSE_OK);
        EventStream<LogEntry> onlyXPathException = deleteOnSignal(latestEvent, XPATH_EVALUATION_EXCEPTION, XPATH_OK);

        EventStream<LogEntry> otherExceptions =
            filterOnCategory(latestEvent, true, PARSE_EXCEPTION, XPATH_EVALUATION_EXCEPTION, SELECTION_EVENT_TRACING)
                .filter(it -> isDeveloperMode() || !it.getCategory().isInternal());

        // none of this is done if developer mode isn't enabled because then those events aren't even pushed in the first place
        @SuppressWarnings("unchecked")
        EventStream<LogEntryWithData<Object>> traces = latestEvent.filter(e -> e.getCategory().isTrace()).map(t -> (LogEntryWithData<Object>) t);
        EventStream<LogEntryWithData<Object>> reducedTraces = ReactfxUtil.reduceEntangledIfPossible(
            traces,
            // the user data for those is the event
            // if they're the same event we reduce them together
            (lastEv, newEv) -> Objects.equals(lastEv.getUserData(), newEv.getUserData()),
            LogEntryWithData::reduceEventTrace,
            EVENT_TRACING_REDUCTION_DELAY
        );

        EventStreams.merge(reducedTraces, onlyParseException, otherExceptions, onlyXPathException)
                    .distinct()
                    .subscribe(fullLog::add);
    }


    @Override
    public DesignerRoot getDesignerRoot() {
        return designerRoot;
    }


    private static EventStream<LogEntry> deleteOnSignal(EventStream<LogEntry> input, Category normal, Category deleteSignal) {
        return VetoableEventStream.vetoableFrom(
            filterOnCategory(input, false, normal, deleteSignal),
            (maybeVetoable) -> maybeVetoable.getCategory() == normal,
            (pending, maybeVeto) -> maybeVeto.getCategory() == deleteSignal,
            (a, b) -> b,
            PARSE_EXCEPTION_REDUCTION_DELAY
        );
    }


    private static EventStream<LogEntry> filterOnCategory(EventStream<LogEntry> input, boolean complement, Category first, Category... selection) {
        EnumSet<Category> considered = EnumSet.of(first, selection);
        EnumSet<Category> complemented = complement ? EnumSet.complementOf(considered) : considered;

        return input.filter(e -> complemented.contains(e.getCategory()));
    }


    @Override
    public void logEvent(LogEntry event) {
        if (event != null) {
            latestEvent.push(event);
        }
    }

    @Override
    public LiveList<LogEntry> getLog() {
        return fullLog;
    }
}
