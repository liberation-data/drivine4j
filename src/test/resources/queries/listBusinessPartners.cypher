MATCH (n:BusinessPartner)
  WHERE n.name STARTS WITH $startsWith
RETURN properties(n)