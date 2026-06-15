I want you to build a video editing application that allows users to trim clips and add transitions. But I want the AI to be able to perform these same actions. For example, I want the user to tell the AI to cut out silent parts or clip any footage where something in particular is in the frames.

I need to be able to see the clips in a timeline where I can scrub and zoom in and out, and select clips write a prompt about and apply effects. I also need to be able to to do that for the audio--separately, even--and crop and specify quality and aspect ratio.

I need to be able to see the clips in a timeline where I can scrub and zoom in and out, and select clips write a prompt about and apply effects. I also need to be able to to do that for the audio--separately, even--and crop and specify quality and aspect ratio.

The global settings should also include Original, maintaining the AR, quality, fps, etc. of the original clip

Add tools here, like zoom, split, delete, etc. Also include the keyframe tool, and implement that functionality. Allow the user to set ease-in/ease-out with a curve editor.
Apply style changes to the selected element(s).

oh, you MUST make sure that the layout rearranges for phones in portrait mode. It should feel as natural in that orientation as landscape, and as natural on a phone as it is on a tablet or lapt Everything must dynamically resize, too, including the audio and video tracks. Let's also go ahead and make that top bar SUPER compact, and include save (and autosave), load, and export options, too. Build out a menu for this.

Implement undo/redo, export presets, batch processing, customizable keyboard shortcuts, filters, and let's integrate other AIs so the user can input their own api keys. Also, is this a PWA?

We're changing the name of this app to Guillotine. Update the branding to something more appropriate. And make sure that the user can save the project. Project files should use the extension .gilt

Add controlls to speed up play, to go to the start and end of the clip and to go forward and backward by a single frame.

Rework the header items into a modern, mobile-friendly dropdown menu.

The user should be able to add another track just by dragging the video or audio clip down or up to create another video or audio track. Put a plus sign in the central tools that will create another video or autio track, whichever is selected. The user should also be able to import images and audio files

And let's add an AI button to that too, so the user can have AI create a clip if they have the api key for one that's capable of it. We should include a free AI option by default, for clip generation and for editing. Just come up with a few free options for generation and for editing, and include them all.

The export presets should include "Original" and many other standard options, for both video and audio, and ALL of them should be removed from the menu but included in a popup window with all the rendering options displayed for the user, like filetype and file name, and how big the resulting file will be. Include a check for disk space before rendering. When the user renders, progress should also be shown on this window. Include the time elapsed and the estimated time remaining as well.

Add a video buffer for the preview. We also need some basic audio editing tools. And image editing tools, for that matter, to be applied across an entire clip. We also need copy and paste functionality, for both clips and for effects a clip has applied to it.

I also want the AI to be able to create effects that can be applied to video or to audio. Build out a framework for that, and make a few to start with. And let's add L/R volume controls and normalization for audio. In general, let's mirror the UI after Sony Vegas

Replace the starry icon with "AI". Replace the app icon with a guillotine. And I want a prompt input box to show when you press the AI button. Context should be enough to know whether the user wants to generate a clip or edit a clip.

I wanI want the entire app monotone except for a bright, high saturation red used to show active or selected buttons, clips, and menu items.





