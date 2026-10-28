package lab.common;

/** What we need from the bearer token: who is logged in and which company (CNPJ) they act for. */
public record Claims(String logon, String document) {
}
