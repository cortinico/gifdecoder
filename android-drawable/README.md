# android-drawable

[ ![Download](https://api.bintray.com/packages/redwarp/maven/gif-android-drawable/images/download.svg) ](https://bintray.com/redwarp/maven/gif-android-drawable/_latestVersion)

An implementation of an Android Drawable, using the decoder, as a simple way to display a gif in an
Android app.

## Setup

`android-drawable` is available on `jcenter()`

Add it to your dependencies with `implementation "net.redwarp.gif:android-drawable:x.y.z"`

In code, create a GifDrawable from any InputStream.
GifDrawable implements `Animatable2Compat`, a user needs to call `start()` to start the animation.

```kotlin
// Example: with a gif included in your app as an asset
val inputStream = context.assets.open("some_animated.gif")

val drawable:GifDrawable = GifDrawable.from(inputStream)
drawable.start()

imageView.setImageDrawable(drawable)
```

## Alternatives

If you target Android 28, [AnimatedImageDrawable](https://developer.android.com/reference/android/graphics/drawable/AnimatedImageDrawable) is good and efficient.
Below 28, it's not crystal clear what should be used.

Coil and Glide both bake their own version of a Gif decoder.

- The Coil version relies on the `Movie` class and is quite expensive in term of memory and CPU usage. See [MovieDrawable](https://github.com/coil-kt/coil/blob/master/coil-gif/src/main/java/coil/drawable/MovieDrawable.kt).
- The Glide version is decent, see [StandardGifDecoder](https://github.com/bumptech/glide/blob/master/third_party/gif_decoder/src/main/java/com/bumptech/glide/gifdecoder/StandardGifDecoder.java).
