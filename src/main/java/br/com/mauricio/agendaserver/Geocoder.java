package br.com.mauricio.agendaserver;

interface Geocoder {
    Result geocode(String normalizedAddress);

    record Result(boolean success, double latitude, double longitude, double confidence,
                  String precision, String provider, String error) {
        static Result failure(String provider, String error) {
            return new Result(false, 0, 0, 0, "", provider, error == null ? "Falha de geocodificação" : error);
        }
    }
}
