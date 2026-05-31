# MyStuff

Android app that captures a photo, lets the user crop it, and uses a local
LiteRT-LM multimodal model to list objects visible in the cropped image.

## Importing a LiteRT-LM model

The app cannot directly reuse a model downloaded inside Google AI Edge Gallery.
Android keeps each app's private storage sandboxed, so this app needs its own
copy of the `.litertlm` model.

1. Download a multimodal `.litertlm` model to the phone.
   A good first test model is Gemma 4 E2B:
   https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main
2. Select `gemma-4-E2B-it.litertlm`.
   Do not use `gemma-4-E2B-it-web.litertlm`; the web-optimized file is
   text-only.
3. Open this app.
4. Tap **Import LiteRT-LM model**.
5. Pick the downloaded `.litertlm` file from Android's file picker, usually
   under **Downloads**.
6. Wait for the copy to finish. The app stores the model internally as
   `/data/data/com.example.mystuff/files/models/object_lister.litertlm`.
7. Tap the camera button, take and crop a photo, then run the local object
   listing.

Keep enough free space for both the downloaded model and the app's internal
copy. Gemma 4 E2B is several GB, and larger variants need more storage.
