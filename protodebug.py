import your_generated_pb2 as pb

# Initialize the empty object type
message = pb.YourMessageName()

# Parse the binary stream directly
message.ParseFromString("binary_response")




# Access your properties natively
print(message.user_id)