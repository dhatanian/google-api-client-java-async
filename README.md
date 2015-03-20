#Google API non blocking client for Java
An extension of the Google API Java client to allow non-blocking calls

##Usage
    Credential credential = ... //Generate an OAuth token
    NIOHttpTransport nioTransport = new NIOHttpTransport();
    Directory directory = new Directory.Builder(nioTransport, new JacksonFactory(), credential);

    GoogleAsyncClient.executeAsync(directory.users().list().setCustomer("my_customer"), new FutureCallback&lt;Users&gt;() {
      @Override
      public void completed(Users result) {
         try {
             System.out.println("Completed, result : " + result.toPrettyString());
         } catch (IOException e) {
             e.printStackTrace();
         }
         done[0] = true;
       }
       @Override
       public void failed(Exception ex) {
         System.err.println("Failed");
         ex.printStackTrace();
         done[0] = true;
       }
       @Override
       public void cancelled() {
         System.err.println("Cancelled");
         done[0] = true;
       }
    });

    while (!done[0]) {
      Thread.sleep(100L);
    }
    nioHttpTransport.shutdown();
    }
