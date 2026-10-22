package com.projector.tvremote;

/** Kunci ADB Termux yang SUDAH ter-authorize di proyektor (jangan diganti). */
public final class AdbKey {
    private AdbKey() {}

    public static final String PRIVATE_PEM =
        "-----BEGIN PRIVATE KEY-----\n" +
        "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC/hNFFYTwpkfoT\n" +
        "Zdia2nTM71E0EYpod2ZLhkRFF/XGBuzxJAwuXoSuqrya8IjQ+pet+k6robBqiTGc\n" +
        "TE9F37SXEs1UooYnfUbrLROPpu+BR5amKn2B7XzXV6wruNQN3S6Wp7Nf2NeaG+B4\n" +
        "HQEenmBTP+L+zXQg4LZHdpzPU02aDyf3arCh+VqLxpmR3C813AWgySKRtKXkZ417\n" +
        "u6F6MDoC0xISoRLMCdcRT7tVCrQeXy918COw67YquRVcGF8vJrSbF+fAzY9QU2b8\n" +
        "xkYbguvdv9BS6IGlsQV8VsSBjxtvZTnKGcq5cfGNjYRc3/mvTJfmZT4RhdtXJjdf\n" +
        "5+2UWQN1AgMBAAECggEAALLMTJN5M7GbltyFOLNZP7ITurvhpFXjIp1fvFzFAa7e\n" +
        "yFZYUQb5NuXagDomVZTEXtcN2GRjj0/mxWol09xn/TENXKSnFpfHXW4xpxF1yCPp\n" +
        "sbtv77xc+zAPqa0n4NEQrW35In1Y8+/sFqQMb/8l3Mfb3RAEv2XyZDpTghIMs2Yf\n" +
        "IFq8czuE6SOlwLSgnII0yPVNVQlMBWmNYETRjlhSieh9kjfnWprs1fEL0K1Tww0o\n" +
        "ARNKPJvAd37i+hQFgXS1GwdvK/eXJW0xMWrbwIgrU9i5mEKS/igKLPlpiYqkESPp\n" +
        "XZ1RWwRgwKxKks0cP3kz/fkvf+oOH4Rqj6jK46pJgQKBgQD2k7KkGEuF5993bQCB\n" +
        "IRATm8mjNa8u4K2ic3DsEzrLFTBtgNCaxh19uyvzdybPUNeCJ/rYFPZs5QMF80gQ\n" +
        "KUw1WVMOWwKX+4f/8ZrcbpvxVpsZ5vaKmNqyxkRZZGyPerUUcPjED2C8UP1Q82q6\n" +
        "bfjHz3pNv8868+ml29WaY3mz1QKBgQDG1noh/ncMtHFsvrGRA9qgcASKx4gUyDMu\n" +
        "tIkFj8q2cmySrBV6sQFMoCycPzXmiiLuZTU6lHj0Y1MCl/H1m5VX6El0SA5TbiOk\n" +
        "hCbyXKbchPHlkUD1Mkq4dBpwhNf8qKktWooHYHHbNQSwopELNzRAXn4/edJvktwV\n" +
        "LxLO86EBIQKBgC41u9LY4r5zmAOPn/qTMaobFgzKMMx+OswpCk6Tw/7vN6Yt7+ox\n" +
        "mrFHdJMqD1Hm+pxf1bjNJOl/ebORxj2RH8EXvmMAV1oYP4KNytHowLzX2DVgfW+3\n" +
        "+bRrR7/g/B9fhbQQ/+3uW9iu7OpQ0/2gQnFfxrHvcmy/7Vex/TPz1IkJAoGAfKRR\n" +
        "5yFWi1ZJi7t2H8tkrxHJJx2xBiYBP5PYGO5sSjYsLT1GMaTnhZsam89cAGozP3Ge\n" +
        "Dlyn/bqNfKC3AhlHYtWvxf6tTEtMeIKGz4KY7r8/y4JkT8/42uuc70+2/SDU/CM3\n" +
        "RyyD8r+ncvpVJcyzKm6RgUTiTyKmZ3kctzItiUECgYEAm6aEUMWX5eh8vTZP4Y6z\n" +
        "9r+BOelNn3OBnEIARZc2fTYJ83ILJw/YDV2FYan8UuLJ7KIR7dVjnbJuZtfyo3a4\n" +
        "FJGBev7aJ3zDyVAneD+CY9z7TJixNmJmXu8Q+oBVYcIwm1SAKAvOOvU/JdpPKVrV\n" +
        "G5Lqc3egX6pdld9t/OCLXLk=\n" +
        "-----END PRIVATE KEY-----\n" +
        "";

    public static final String PUBLIC_LINE =
        "QAAAACOLpPx1A1mU7edfNyZX24URPmXml0yv+d9chI2N8XG5yhnKOWVvG4+BxFZ8BbGlgehS0L/d64IbRsb8ZlNQj83A5xebtCYvXxhcFbkqtuuwI/B1L18etApVu08R1wnMEqESEtMCOjB6obt7jWfkpbSRIsmgBdw1L9yRmcaLWvmhsGr3Jw+aTVPPnHZHtuAgdM3+4j9TYJ4eAR144Bua19hfs6eWLt0N1LgrrFfXfO2BfSqmlkeB76aPEy3rRn0nhqJUzRKXtN9FT0ycMYlqsKGrTvqtl/rQiPCavKquhF4uDCTx7AbG9RdFRIZLZndoihE0Ue/MdNqa2GUT+pEpPGFF0YS/tTSblVR5IBXS3QCxXzoeN6AEaYEOBkzzYZOiWN4o0+YXklRLP/CD9P2erU8ahlmLmYuGYiArIjQrPT/2MNxFyWUk0uUSlcPlr8f9d9wZ1fZwF8QjUjMB3eAZR2hGZJfETzbcwiJYyTpHgLP42B2Cp4gqP2XcAWwHEgibmw/eB2y2V4Q1jLJOpD9VgumbKfigICD9l44f48m0htt1YykAax2EcQ7aB5qAPRiszO4vB/caOgaDjSaAQpeG9c3K043WghEppxZlcTZZ3rhqGx7keZmE/GP3DQLPey7J/++AvzxXATJfRZh12kuKtqopwB7P49Zw4cADXuwzFegHoVinPQEAAQA= u0_a424@localhost";
}
