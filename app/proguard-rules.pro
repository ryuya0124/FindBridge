# MapLibre and AndroidX provide their JNI and WebView consumer rules.
# Keep JavaScript adapter methods protected by AndroidX's origin-restricted bridge.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
