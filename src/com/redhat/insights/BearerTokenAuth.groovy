

class BearerTokenAuth extends TokenAuth {
    @Override
    public void setAuthorizationHeader(URLConnection connection, BuildContext context) throws IOException {
        connection.setRequestProperty("Authorization", "Bearer: " + this.getApiToken())
    }
}
