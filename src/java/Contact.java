/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/JSP_Servlet/Servlet.java to edit this template
 */

import java.io.*;
import java.sql.*;
import javax.servlet.*;
import javax.servlet.http.*;

public class Contact extends HttpServlet {
@Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String name=request.getParameter("name");
        String email=request.getParameter("email");
        String sub=request.getParameter("subject");
        String msg=request.getParameter("message");
        RequestDispatcher dis=null;
        Connection con=null;

        try{
            Class.forName("com.mysql.cj.jdbc.Driver");
            con=DriverManager.getConnection("jdbc:mysql://localhost:3306/progenie?useSSL=false","root", "root");
            PreparedStatement ps=con.prepareStatement("insert into contact(name,email,subject,message) values (?,?,?,?)");
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, sub);
            ps.setString(4, msg);
            int row=ps.executeUpdate();
            dis=request.getRequestDispatcher("index.jsp");
            if(row>0)
                request.setAttribute("status", "success");
            else
                request.setAttribute("status", "failed");
        }
        catch(Exception e){
            e.printStackTrace();
        }
        finally{
            try{
                con.close();     
                dis.forward(request, response);
            }
            catch(Exception e){
                e.printStackTrace();
            }
        }
    }
}
