package pl.detailing.crm.gus.exception

class CompanyNotFoundException(nip: String) :
    RuntimeException("Firma z NIP $nip nie została odnaleziona w rejestrze GUS/REGON")

class GusServiceUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class InvalidNipException(nip: String) :
    RuntimeException("Nieprawidłowy numer NIP: '$nip'. Sprawdź poprawność cyfry kontrolnej")
