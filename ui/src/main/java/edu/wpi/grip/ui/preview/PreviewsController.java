package edu.wpi.grip.ui.preview;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import com.google.common.math.IntMath;
import edu.wpi.grip.core.OutputSocket;
import edu.wpi.grip.core.Pipeline;
import edu.wpi.grip.core.Source;
import edu.wpi.grip.core.Step;
import edu.wpi.grip.core.events.SocketPreviewChangedEvent;
import edu.wpi.grip.core.events.StepMovedEvent;
import edu.wpi.grip.ui.pipeline.PipelineController;
import edu.wpi.grip.ui.pipeline.source.SourceController;
import edu.wpi.grip.ui.util.GRIPPlatform;
import javafx.fxml.FXML;
import javafx.scene.layout.HBox;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/**
 * Controller for a container that automatically shows previews of all sockets marked as "previewed".
 *
 * @see OutputSocket#isPreviewed()
 */
@Singleton
public class PreviewsController {

    @FXML
    private HBox previewBox;
    @Inject
    private EventBus eventBus;
    @Inject
    private PipelineController pipelineController;
    @Inject
    private Pipeline pipeline;
    @Inject
    private SocketPreviewViewFactory previewViewFactory;
    @Inject
    private GRIPPlatform platform;

    private final List<OutputSocket<?>> previewedSockets = new ArrayList<>();

    /**
     * This function is called when a step moves in the pipeline to adjust the positions of any open previews it has
     * to reflect the new order of the pipeline.
     */
    @Subscribe
    public synchronized void onPreviewOrderChanged(StepMovedEvent event) {
        platform.runAsSoonAsPossible(() -> {//Run this function on the main gui thread
            final Step movedStep = event.getStep(); //The step whose position in the pipeline has changed
            final int distanceMoved = event.getDistance(); //The number of indices (positive or negative) the step has been moved by
            final int numberOfSourcePreviews = getNumbOfSourcePreviews();//The number of previews opened that are displaying sources (NOT steps)

            final OutputSocket<?>[] socketsMovedArray = movedStep.getOutputSockets();//Grab all the output sockets of the step that has moved

            //Find the rightmost and leftmost position in the previews of the previewed sockets of the step that has moved
            int rightmostIndex = 0; //Set to minimum possible value so that the first index will overwrite it
            int leftmostIndex = this.previewedSockets.size();//Set to maximum possible value so that the first index will overwrite it

            Stack<OutputSocket<?>> previewedMovedSockets = new Stack<OutputSocket<?>>();//This will hold the sockets of the step that was moved that are open for preview

            for (OutputSocket<?> i : socketsMovedArray) {
                if (this.previewedSockets.indexOf(i) != -1) {//If this socket is previewed
                    previewedMovedSockets.push(i);

                    if (rightmostIndex < this.previewedSockets.indexOf(i)) {
                        rightmostIndex = this.previewedSockets.indexOf(i);
                    }

                    if (leftmostIndex > this.previewedSockets.indexOf(i)) {
                        leftmostIndex = this.previewedSockets.indexOf(i);
                    }

                }
            }

            //Deal with each previewed socket from the step that was moved in turn
            while (previewedMovedSockets.size() != 0) { //While there are still sockets to deal with on the stack
                OutputSocket<?> current = previewedMovedSockets.pop();//Grab the top socket on the stack
                int oldIndex = this.previewedSockets.indexOf(current);//Get the index of this preview so we can remove the correct entry

                int newLocation = 0;//This will hold the new index in the list of previewed sockets for this socket

                if (distanceMoved < 0) { //If the step moved left....
                    newLocation = leftmostIndex + distanceMoved; //Calculate the new index from the leftmost previewed socket of this step
                } else { //The step must have moved right....
                    newLocation = rightmostIndex + distanceMoved;//So calculate the new index from the rightmost previewed socket of this step
                }

                if (newLocation < numberOfSourcePreviews) {//If the new calculated index would put it in the midst of source previews
                    newLocation = numberOfSourcePreviews;//Make the index the location of the first non-source preview
                } else { //The new index is the current location of another step (NOT a source)

                    //So we need to make sure that we jump over GROUPS of previews associated with the SAME step as a unit
                    int count = 0;//This will hold the number of previews open from the same step in sequence, starting from the new location and going in the direction we are moving

                    if (distanceMoved < 0) {//If the step moved left....
                        OutputSocket<?> nextSocketInDirection = this.previewedSockets.get(newLocation);//Grab the socket whose preview is open at the new location
                        boolean zeroReached = false;//We will set this to true if we reach the beginning of the list of previews (there are no source previews open)
                        while ((!zeroReached) &&
                                ((nextSocketInDirection.getStep().isPresent())
                                        && (nextSocketInDirection.getStep().get() == this.previewedSockets.get(newLocation).getStep().get()))) { //While we haven't reached the beginning of the list of previews, the socket at this location is a socket from a step, and it is the SAME step as the step of the socket at the new location...
                            count++;
                            if ((newLocation - count) > 0) {//If we haven't reached the beginning of the list of open previews...
                                nextSocketInDirection = this.previewedSockets.get(newLocation - count);//Grab the next previewed socket to examine in the direction we are moving
                            } else {
                                zeroReached = true;//Mark that we've reached the beginning of the list of previews so we know to stop looking for more
                            }
                        }
                        newLocation = newLocation - (count - 1);//Since the first compare of the while loop will always be true, we subract one from the count when we use it to adjust newLocation

                    } else {//The step must have moved right....
                        while ((newLocation + count < this.previewedSockets.size())
                                && (this.previewedSockets.get(newLocation + count).getStep().get() == this.previewedSockets.get(newLocation).getStep().get())) { //While there are still previewed sockets to examine, and the socket being examined is one from the SAME step of the socket at the new location....
                            count++;
                        }
                        newLocation = newLocation + (count - 1);//Since the first compare of the while loop will always be true, we subract one from the count when we use it to adjust newLocation
                    }
                }

                //Remove this socket from the old point in the previews
                this.previewedSockets.remove(oldIndex);
                this.eventBus.unregister(this.previewBox.getChildren().remove(oldIndex));

                if (newLocation > this.previewedSockets.size()) {//If the new index is now too big for the list of previews
                    newLocation = this.previewedSockets.size();//Make it so it will be added to the end of the list of previews
                }
                this.previewedSockets.add(newLocation, current);//...add it to the correct location in the list of previews open
                this.previewBox.getChildren().add(newLocation, previewViewFactory.create(current));//...and display it in the correct location in the list of previews open
            }
        });
    }

    /**
     * This function is called when a preview button is pushed/triggered
     */
    @Subscribe
    public synchronized void onSocketPreviewChanged(SocketPreviewChangedEvent event) {
        platform.runAsSoonAsPossible(() -> {//Run this function on the main gui thread

            final OutputSocket<?> socket = event.getSocket(); //The socket whose preview has changed

            if (socket.isPreviewed()) {// If the socket was just set as previewed, add it to the list of previewed sockets and add a new view for it.

                if (!this.previewedSockets.contains(socket)) {//If the socket is not already previewed...

                    if (socket.getStep().isPresent()) { //If this is a socket associated with a pipeline step (IE NOT a source)....

                        //Find the appropriate index to add this preview with...
                        int indexInPreviews = getIndexInPreviewsOfAStepSocket(socket);

                        this.previewedSockets.add(indexInPreviews, socket);//...use this index to add it to the correct location in the list of previews open
                        this.previewBox.getChildren().add(indexInPreviews, previewViewFactory.create(socket));//...and display it in the correct location in the list of previews open in the gui

                    } else {//This is a socket associated with a source and not a pipeline step...

                        //Find the appropriate index to add this preview with.
                        int indexInSourcePreviews = getIndexInPreviewsOfASourceSocket(socket);

                        this.previewedSockets.add(indexInSourcePreviews, socket);//Add the preview to the appropriate place in the list of previewed sockets
                        this.previewBox.getChildren().add(indexInSourcePreviews, previewViewFactory.create(socket));//Display the preview in the appropriate place
                    }
                }
            } else {//The socket was already previewed, so the user must be requesting to not show this preview (remove both it and the corresponding control)

                int index = this.previewedSockets.indexOf(socket);//Get the index of this preview so we can remove the correct entry
                if (index != -1) {//False when the preview isn't currently displayed
                    this.previewedSockets.remove(index);
                    this.eventBus.unregister(this.previewBox.getChildren().remove(index));
                }
            }
        });
    }

    /**
     * Find the correct index in the displayed previews for a socket associated with a source (NOT a step socket)
     * by comparing the indices in the pipeline.
     * Made to be called in {@link PreviewsController#onSocketPreviewChanged}
     *
     * @param socket An output socket associated with a source (NOT a step)
     * @return The correct index (an int) in the list of displayed previews for the given <code>socket</code>
     * @see PreviewsController#onSocketPreviewChanged(SocketPreviewChangedEvent)
     */
    private int getIndexInPreviewsOfASourceSocket(OutputSocket<?> socket) {
        final Source socketSource = socket.getSource().get();//The source socket associated with the socket whose preview has changed
        final SourceController sourceView = this.pipelineController.findSourceView(socketSource);//The gui object that displays the socketSource
        int indexOfSource = this.pipeline.getSources().indexOf(sourceView); //The index of the source that has the socket in the pipeline

        //Start with the first socket in the list of previewed sockets
        int indexInSourcePreviews = 0;
        //Find the correct index in the displayed source previews by comparing the indices
        while (((this.previewedSockets.size() > indexInSourcePreviews)//If there are previews still to be examined AND
                && (this.previewedSockets.get(indexInSourcePreviews).getSource().isPresent()))//AND If the preview at this index is a source...
                && ((this.pipeline.getSources().indexOf(this.pipelineController.findSourceView(this.previewedSockets.get(indexInSourcePreviews).getSource().get()))) < indexOfSource)) {//AND the preview at this index is a source with an index in the list of sources less than this source
            indexInSourcePreviews++;
        }
        return indexInSourcePreviews;
    }

    /**
     * Find the correct index in the displayed previews for a socket associated with a step (NOT a source socket)
     * by comparing the indices in the pipeline, starting with the first non-source preview displayed.
     * Made to be called in {@link PreviewsController#onSocketPreviewChanged}
     *
     * @param socket An output socket associated with a step (NOT a source)
     * @return The correct index in the list of displayed previews for the given <code>socket</code>
     * @see PreviewsController#onSocketPreviewChanged(SocketPreviewChangedEvent)
     */
    private int getIndexInPreviewsOfAStepSocket(OutputSocket<?> socket) {
        int numbOfSourcePreviews = getNumbOfSourcePreviews();//Count how many *source* previews (not *step* previews) are currently displayed

        final Step socketStep = socket.getStep().get();//The pipeline step associated with the socket whose preview has changed
        int indexOfStep = this.pipeline.getSteps().indexOf(socketStep); //The index of the step that has the socket in the pipeline

        //Start at the first non-source socket in the list of previewed sockets
        long indexInPreviews =
                // The socket at this index in the list of displayed sockets has an index in the pipeline less than the socket passed in as "socket"
                this.previewedSockets.stream().filter(outSocket -> outSocket.getStep().isPresent() && this.pipeline.getSteps().indexOf(outSocket.getStep().get()) < indexOfStep).count();
        return IntMath.checkedAdd(Math.toIntExact(indexInPreviews), numbOfSourcePreviews);
    }

    /**
     * Counts how many source previews (NOT step previews) are currently displayed.
     * Called in  {@link PreviewsController#getIndexInPreviewsOfAStepSocket} and {@link PreviewsController#onPreviewOrderChanged(StepMovedEvent)}
     *
     * @return The number of source (NOT step) previews that are currently displayed
     * @see PreviewsController#getIndexInPreviewsOfAStepSocket(OutputSocket)
     * @see PreviewsController#onPreviewOrderChanged(StepMovedEvent)
     */
    private int getNumbOfSourcePreviews() {
        //Start at the beginning of the list.
        int numbOfSourcePreviews = 0;
        while ((this.previewedSockets.size() > numbOfSourcePreviews) //While there are still previews to examine
                && (!this.previewedSockets.get(numbOfSourcePreviews).getStep().isPresent())) { //If this is a source...
            numbOfSourcePreviews++;
        }
        return numbOfSourcePreviews;
    }
}
