from java import jclass
MessagesController = jclass("org.telegram.messenger.MessagesController")
SendMessagesHelper = jclass("org.telegram.messenger.SendMessagesHelper")
def messages_controller(account): return MessagesController.getInstance(account)
def send_messages_helper(account): return SendMessagesHelper.getInstance(account)
