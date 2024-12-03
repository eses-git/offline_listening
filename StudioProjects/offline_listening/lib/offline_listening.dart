import 'package:flutter/services.dart';

class OfflineListening {
  static const MethodChannel _channel = MethodChannel('flutter_mfcc_plugin');

  /// Checks if the TensorFlow Lite model is loaded correctly.
  /// Returns true if loaded successfully, false otherwise.
  static Future<bool> isModelLoaded(String modelPath) async {
    final bool isLoaded = await _channel.invokeMethod(
      'isModelLoaded',
      {'modelPath': modelPath},
    );
    return isLoaded;
  }

  /// Starts the listening service with models and their input shapes.
  /// - `modelPaths`: List of paths to the TensorFlow Lite models.
  /// - `shapes`: Corresponding input shapes for each model.
  static Future<void> startListening(List<String> modelPaths, List<List<int>> shapes) async {
    try {
      await _channel.invokeMethod('startListening', {
        'modelPaths': modelPaths,
        'shapes': shapes,
      });
    } on PlatformException catch (e) {
      print("Error: ${e.message}");
    }
  }

  /// Stops the listening service.
  static Future<void> stopListening() async {
    try {
      await _channel.invokeMethod('stopListening');
    } on PlatformException catch (e) {
      print("Error: ${e.message}");
    }
  }
}
